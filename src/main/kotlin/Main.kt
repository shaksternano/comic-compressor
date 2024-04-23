package io.github.shaksternano.comiccompressor

import com.fewlaps.slimjpg.SlimJpg
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

fun main(args: Array<String>) {
    val startTime = System.currentTimeMillis()

    val options = Options()

    val inputOption = Option("i", "input", true, "Input directory")
    inputOption.isRequired = false
    options.addOption(inputOption)

    val outputOption = Option("o", "output", true, "Output directory")
    outputOption.isRequired = false
    options.addOption(outputOption)

    val compressionLevelOption = Option("c", "compression-level", true, "Compression level")
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
        println("Compression level must be between 0 and 100%")
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

    @OptIn(ExperimentalPathApi::class)
    val comicFiles = inputDirectory.walk()
        .filter {
            it.extension.equals("cbz", true) && !it.startsWith(outputDirectory)
        }
        .toList()
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
        }.onFailure {
            println("Failed to compress $path")
            it.printStackTrace()
        }
    }

    val endTime = System.currentTimeMillis()
    val runtime = (endTime - startTime).milliseconds
    println("Finished in $runtime")
}

private fun compressComic(comicFile: Path, output: Path, compressionLevel: Double, comicNumber: Int, totalComics: Int) {
    val tempDir = createTempDirectory(comicFile.nameWithoutExtension + "-" + comicFile.extension)
    tempDir.toFile().deleteOnExit()
    FileSystems.newFileSystem(comicFile).use { zipFileSystem ->
        val paths = zipFileSystem.rootDirectories.flatMap {
            @OptIn(ExperimentalPathApi::class)
            it.walk()
        }
        println("Compressing $comicFile...")
        ProgressBar.wrap(paths, "Comic $comicNumber/$totalComics").forEach { zippedFile ->
            val tempFile = tempDir.resolve(zippedFile.toString().removePrefix("/"))
            tempDir.relativize(tempFile).forEach {
                it.toFile().deleteOnExit()
            }
            tempFile.createParentDirectories()
            val extension = zippedFile.extension
            if (extension.equals("jpg", ignoreCase = true) || extension.equals("jpeg", ignoreCase = true)) {
                runCatching {
                    val bytes = compressJpg(zippedFile, compressionLevel)
                    tempFile.writeBytes(bytes)
                    return@forEach
                }.onFailure {
                    println("Failed to compress $zippedFile in $comicFile")
                    it.printStackTrace()
                }
            }
            zippedFile.copyTo(tempFile, overwrite = true)
        }
    }
    zipFile(tempDir, output)
    runCatching {
        @OptIn(ExperimentalPathApi::class)
        tempDir.deleteRecursively()
    }
}

private fun compressJpg(jpgFile: Path, compressionLevel: Double): ByteArray =
    SlimJpg.file(jpgFile.inputStream())
        .maxVisualDiff(compressionLevel)
        .keepMetadata()
        .optimize()
        .picture

private fun zipFile(path: Path, output: Path) {
    val outputStream = output.outputStream()
    ZipOutputStream(outputStream).use { zipOutputStream ->
        zipFile(path, path.name, zipOutputStream)
    }
}

private fun zipFile(path: Path, filename: String, zipOutputStream: ZipOutputStream) {
    if (path.isHidden()) {
        return
    }
    if (path.isDirectory()) {
        val nextEntry = if (filename.endsWith("/")) {
            ZipEntry(filename)
        } else {
            ZipEntry("$filename/")
        }
        zipOutputStream.putNextEntry(nextEntry)
        path.listDirectoryEntries().forEach { entry ->
            zipFile(entry, filename + "/" + entry.name, zipOutputStream)
        }
    } else {
        path.inputStream().use { inputStream ->
            val zipEntry = ZipEntry(filename)
            zipOutputStream.putNextEntry(zipEntry)
            inputStream.transferTo(zipOutputStream)
        }
    }
}
