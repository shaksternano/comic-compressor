package io.github.shaksternano.comiccompressor

import com.fewlaps.slimjpg.SlimJpg
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.tongfei.progressbar.ProgressBar
import org.apache.commons.cli.*
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_COMPRESSION_LEVEL: Double = 0.5

@OptIn(ExperimentalPathApi::class)
suspend fun main(args: Array<String>) {
    val startTime = System.currentTimeMillis()

    val options = Options()

    val inputOption = Option("i", "input", true, "Input directory. Default is current directory.")
    inputOption.isRequired = false
    options.addOption(inputOption)

    val outputOption = Option("o", "output", true, "Output directory. Default is current directory.")
    outputOption.isRequired = false
    options.addOption(outputOption)

    val compressionLevelOption = Option(
        "c",
        "compression-level",
        true,
        "Compression level between 0 and 100. Higher values result in more compression. Default is $DEFAULT_COMPRESSION_LEVEL."
    )
    compressionLevelOption.isRequired = false
    options.addOption(compressionLevelOption)

    val parser = DefaultParser()
    val formatter = HelpFormatter()
    val cmd = try {
        parser.parse(options, args)
    } catch (e: ParseException) {
        println(e.message)
        formatter.printHelp("utility-name", options)
        exitProcess(1)
    }

    val inputPathString = cmd.getOptionValue("input")
    val outputPathString = cmd.getOptionValue("output")
    val compressionLevelString = cmd.getOptionValue("compression-level")

    val inputDirectory = inputPathString?.let { Path(it) } ?: Path("")
    val outputDirectory = (outputPathString?.let { Path(it) } ?: Path("compressed-comics")).let {
        if (it.exists() && it.isSameFileAs(inputDirectory)) {
            val outputDirectoryName = "compressed-comics"
            it.resolve(outputDirectoryName).also { newOutputDirectory ->
                if (newOutputDirectory.isRegularFile()) {
                    println("Cannot create output directory $outputDirectoryName because a file with the same name already exists")
                    exitProcess(1)
                }
            }
        } else {
            it
        }
    }
    val compressionLevel = runCatching {
        compressionLevelString?.toDouble()
    }.getOrElse {
        println("Compression level must be a number")
        exitProcess(1)
    } ?: DEFAULT_COMPRESSION_LEVEL
    if (compressionLevel !in 0.0..100.0) {
        println("Compression level must be between 0 and 100")
        exitProcess(1)
    }

    if (!inputDirectory.isDirectory()) {
        println("Input directory does not exist")
        return
    }
    if (outputDirectory.isRegularFile()) {
        println("Output must be a directory")
        return
    }
    outputDirectory.createDirectories()

    val comicFiles = inputDirectory.walk()
        .filter {
            it.extension.equals("cbz", true) && !it.startsWith(outputDirectory)
        }
        .toList()

    println("Compressing ${comicFiles.size} comics at $compressionLevel% compression...")
    println()
    comicFiles.forEachIndexed { index, path ->
        runCatching {
            val resolvedOutputDirectory = path.parent?.let { parent ->
                val intermediateDirectories = inputDirectory.relativize(parent)
                outputDirectory.resolve(intermediateDirectories).also {
                    it.createDirectories()
                }
            } ?: outputDirectory
            val outputPath = resolvedOutputDirectory.resolve(path.fileName)
            compressComic(path, outputPath, compressionLevel, index + 1, comicFiles.size)
            if (index < comicFiles.size - 1) {
                println()
            }
        }.onFailure {
            println("Failed to compress $path")
            it.printStackTrace()
        }
    }

    val endTime = System.currentTimeMillis()
    val runtime = (endTime - startTime).milliseconds
    println("Finished in $runtime")

    val inputSize = comicFiles.sumOf { it.fileSize() }
    val outputSize = outputDirectory.walk().sumOf { it.fileSize() }
    val difference = inputSize - outputSize
    println("Total size reduced from ${toMb(inputSize)}MB to ${toMb(outputSize)}MB, saved ${toMb(difference)}MB")
}

@OptIn(ExperimentalPathApi::class)
private suspend fun compressComic(
    comicFile: Path,
    output: Path,
    compressionLevel: Double,
    comicNumber: Int,
    totalComics: Int
) {
    println("Compressing comic $comicNumber/$totalComics \"$comicFile\"...")
    val tempDir = createTempDirectory(comicFile.nameWithoutExtension + "-" + comicFile.extension)
    tempDir.toFile().deleteOnExit()
    withContext(Dispatchers.IO) {
        FileSystems.newFileSystem(comicFile)
    }.use { zipFileSystem ->
        val paths = zipFileSystem.rootDirectories.flatMap {
            it.walk()
        }
        ProgressBar("Compressing images", paths.size.toLong()).use { progressBar ->
            coroutineScope {
                val progressBarStepChannel = Channel<Unit>()
                launch {
                    for (unused in progressBarStepChannel) {
                        progressBar.step()
                    }
                }
                val concurrentTasks = Runtime.getRuntime().availableProcessors()
                paths.asSequence()
                    .chunked(concurrentTasks)
                    .forEach { zippedPaths ->
                        val compressedImages = zippedPaths.map { zippedPath ->
                            zippedPath to zippedPath.readBytes()
                        }.parallelMap { (zippedFile, imageBytes) ->
                            zippedFile to runCatching {
                                compressJpg(imageBytes, compressionLevel)
                            }.getOrElse {
                                println("Failed to compress $zippedFile in $comicFile")
                                it.printStackTrace()
                                imageBytes
                            }.also {
                                progressBarStepChannel.send(Unit)
                            }
                        }
                        compressedImages.forEach { (zippedFile, compressedImageBytes) ->
                            val tempFileDir = tempDir.resolve(zippedFile.parent.toString().removePrefix("/"))
                            val tempFile = tempFileDir.resolve(zippedFile.nameWithoutExtension + ".jpg")
                            tempDir.relativize(tempFile).forEach {
                                it.toFile().deleteOnExit()
                            }
                            tempFile.createParentDirectories()
                            tempFile.writeBytes(compressedImageBytes)
                        }
                    }
                progressBarStepChannel.close()
            }
        }
    }
    println("Finalizing \"$comicFile\"...")
    zipDirectoryContents(tempDir, output)
    println("Finished compressing \"$comicFile\"")
    val inputSize = comicFile.fileSize()
    val outputSize = output.fileSize()
    val difference = inputSize - outputSize
    println("Size reduced from ${toMb(inputSize)}MB to ${toMb(outputSize)}MB, saved ${toMb(difference)}MB")
    runCatching {
        tempDir.deleteRecursively()
    }
}

suspend fun <T, R> Iterable<T>.parallelMap(transform: suspend (T) -> R): List<R> =
    if (this is Collection && size <= 1)
        map { transform(it) }
    else coroutineScope {
        map { async { transform(it) } }.awaitAll()
    }

private fun compressJpg(jpgBytes: ByteArray, compressionLevel: Double): ByteArray =
    SlimJpg.file(jpgBytes)
        .maxVisualDiff(compressionLevel)
        .keepMetadata()
        .optimize()
        .picture

private fun zipDirectoryContents(path: Path, output: Path) {
    val outputStream = output.outputStream()
    ZipOutputStream(outputStream).use { zipOutputStream ->
        zipDirectoryContents(path, path, path.name, zipOutputStream)
    }
}

private fun zipDirectoryContents(path: Path, root: Path, filename: String, zipOutputStream: ZipOutputStream) {
    if (path.isHidden()) {
        return
    }
    if (path.isDirectory()) {
        if (path == root) {
            path.listDirectoryEntries().forEach { entry ->
                zipDirectoryContents(entry, root, entry.name, zipOutputStream)
            }
        } else {
            val nextEntry = if (filename.endsWith("/")) {
                ZipEntry(filename)
            } else {
                ZipEntry("$filename/")
            }
            zipOutputStream.putNextEntry(nextEntry)
            path.listDirectoryEntries().forEach { entry ->
                zipDirectoryContents(entry, root, filename + "/" + entry.name, zipOutputStream)
            }
        }
    } else {
        path.inputStream().use { inputStream ->
            val zipEntry = ZipEntry(filename)
            zipOutputStream.putNextEntry(zipEntry)
            inputStream.transferTo(zipOutputStream)
        }
    }
}

fun toMb(bytes: Long): Long =
    (bytes / 1024.0 / 1024.0).toLong()
