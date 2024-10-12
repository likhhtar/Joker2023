package me.markoutte.joker.parseJSON.step1

import me.markoutte.joker.helpers.ComputeClassWriter
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.objectweb.asm.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.random.Random

@ExperimentalStdlibApi
fun main(args: Array<String>) {
    val options = Options().apply {
        addOption("c", "class", true, "Java class fully qualified name")
        addOption("m", "method", true, "Method to be tested")
        addOption("cp", "classpath", true, "Classpath with libraries")
        addOption("t", "timeout", true, "Maximum time for fuzzing in seconds")
        addOption("s", "seed", true, "The source of randomness")
    }
    val parser = DefaultParser().parse(options, args)
    val className = parser.getOptionValue("class")
    val methodName = parser.getOptionValue("method")
    val classPath = parser.getOptionValue("classpath")
    val timeout = parser.getOptionValue("timeout")?.toLong() ?: 10L
    val seed = parser.getOptionValue("seed")?.toInt() ?: Random.nextInt()
    val random = Random(seed)

    println("Running: $className.$methodName with seed = $seed")
    val errorSet = mutableSetOf<String>()
    val dataBuffer = ByteArray(300)
    val startTime = System.nanoTime()

    val targetMethod = try {
        locateMethod(className, methodName, classPath)
    } catch (t: Throwable) {
        println("Method $className#$methodName not found")
        return
    }

    val initialInputs = mutableMapOf(
        -1 to """<!DOCTYPE html><html><head></head><body></body></html>""".toByteArrayWithLimit(dataBuffer.size),
        0 to """<html><body><h1>Test</h1></body></html>""".toByteArrayWithLimit(dataBuffer.size),
        1 to """<html><p>Invalid HTML <div><span></body></html>""".toByteArrayWithLimit(dataBuffer.size)
    )

    while (System.nanoTime() - startTime < TimeUnit.SECONDS.toNanos(timeout)) {
        val mutatedBuffer = initialInputs.values.randomOrNull(random)?.let(Random::alterData)
            ?: dataBuffer.apply(random::nextBytes)
        val arguments = createArguments(targetMethod, mutatedBuffer)
        val argumentsString = "${targetMethod.name}: ${arguments.contentDeepToString()}"

        try {
            PathTracker.id = 0
            targetMethod.invoke(null, *arguments).apply {
                val seedId = PathTracker.id
                if (initialInputs.putIfAbsent(seedId, mutatedBuffer) == null) {
                    println("New input seed added: ${seedId.toHexString()}")
                }
            }
        } catch (e: InvocationTargetException) {
            recordError(e.targetException, errorSet, argumentsString, mutatedBuffer)
        } catch (e: Throwable) {
            recordError(e, errorSet, argumentsString, mutatedBuffer)
        }
    }

    println("Inputs generated: ${initialInputs.size}")
    println("Errors recorded: ${errorSet.size}")
    println("Elapsed time: ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)} ms")
}

fun recordError(e: Throwable, errorSet: MutableSet<String>, argumentsString: String, buffer: ByteArray) {
    if (errorSet.add(e::class.qualifiedName!!)) {
        val errorName = e::class.simpleName
        println("New error detected: $errorName")
        val path = Paths.get("error_report_$errorName.txt")
        Files.write(path, listOf(
            "${e.stackTraceToString()}\n",
            "$argumentsString\n",
            "${buffer.contentToString()}\n"
        ))
        Files.write(path, buffer, StandardOpenOption.APPEND)
        println("Error log saved to: ${path.fileName}")
    }
}

fun locateMethod(className: String, methodName: String, classPath: String): Method {
    val libraries = classPath.split(File.pathSeparatorChar).map { File(it).toURI().toURL() }.toTypedArray()
    val classLoader = object : URLClassLoader(libraries) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            return if (name.startsWith(className.substringBeforeLast('.'))) {
                modifyAndLoadClass(name).apply { if (resolve) resolveClass(this) }
            } else {
                super.loadClass(name, resolve)
            }
        }

        fun modifyAndLoadClass(name: String): Class<*> {
            val classFile = name.replace('.', '/')
            val classBytes = getResourceAsStream("$classFile.class")!!.use { it.readBytes() }
            val reader = ClassReader(classBytes)
            val writer = ComputeClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES, this)
            val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    return object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        override fun visitLineNumber(line: Int, start: Label?) {
                            visitFieldInsn(Opcodes.GETSTATIC, PathTracker.javaClass.canonicalName.replace('.', '/'), "id", "I")
                            visitLdcInsn(line)
                            visitInsn(Opcodes.IADD)
                            visitFieldInsn(Opcodes.PUTSTATIC, PathTracker.javaClass.canonicalName.replace('.', '/'), "id", "I")
                            super.visitLineNumber(line, start)
                        }
                    }
                }
            }
            reader.accept(visitor, ClassReader.SKIP_FRAMES)
            return defineClass(name, writer.toByteArray(), 0, writer.toByteArray().size)
        }
    }
    return classLoader.loadClass(className).declaredMethods.first {
        "${it.name}(${it.parameterTypes.joinToString(",") { c -> c.typeName }})" == methodName
    }
}

fun createArguments(method: Method, data: ByteArray): Array<Any> {
    val buffer = ByteBuffer.wrap(data)
    return Array(method.parameterTypes.size) {
        when (method.parameterTypes[it]) {
            Int::class.java -> buffer.get().toInt()
            IntArray::class.java -> IntArray(buffer.get().toUByte().toInt()) { buffer.get().toInt() }
            String::class.java -> String(ByteArray(buffer.get().toUByte().toInt() + 1) { buffer.get() }, Charset.forName("koi8"))
            else -> error("Unsupported parameter type: ${method.parameterTypes[it]}")
        }
    }
}

object PathTracker {
    @JvmField var id: Int = 0
}

fun Random.alterData(buffer: ByteArray): ByteArray {
    val htmlString = buffer.toString(Charsets.UTF_8)
    val mutatedHtml = StringBuilder(htmlString)
    val addTagProb = 0.3
    val removeTagProb = 0.05
    val changeAttrProb = 0.2
    val invalidCharProb = 0.1
    val duplicateTagProb = 0.1

    val tagRegex = "<[^>]+>".toRegex()
    val tags = tagRegex.findAll(htmlString).map { it.value }.toList()

    if (tags.isNotEmpty() && Random.nextDouble() < addTagProb) {
        val randomTag = tags.random()
        val insertionPoint = Random.nextInt(mutatedHtml.length)
        mutatedHtml.insert(insertionPoint, randomTag)
    }

    if (tags.isNotEmpty() && Random.nextDouble() < removeTagProb) {
        val randomTag = tags.random()
        val tagIndex = mutatedHtml.indexOf(randomTag)
        if (tagIndex != -1) {
            mutatedHtml.delete(tagIndex, tagIndex + randomTag.length)
        }
    }

    if (tags.isNotEmpty() && Random.nextDouble() < changeAttrProb) {
        val randomTag = tags.random()
        val tagIndex = mutatedHtml.indexOf(randomTag)
        if (tagIndex != -1) {
            val newTag = randomTag.replace(Regex("=\"[^\"]*\""), "=\"${Random.nextInt(100)}\"")
            mutatedHtml.replace(tagIndex, tagIndex + randomTag.length, newTag)
        }
    }

    if (Random.nextDouble() < invalidCharProb) {
        val insertionPoint = Random.nextInt(mutatedHtml.length)
        mutatedHtml.insert(insertionPoint, Random.nextInt(32, 127).toChar())
    }

    if (tags.isNotEmpty() && Random.nextDouble() < duplicateTagProb) {
        val randomTag = tags.random()
        val insertionPoint = Random.nextInt(mutatedHtml.length)
        mutatedHtml.insert(insertionPoint, randomTag)
    }

    return mutatedHtml.toString().toByteArray(Charsets.UTF_8)
}

fun Any.toByteArrayWithLimit(length: Int): ByteArray? = when (this) {
    is String -> {
        val bytes = toByteArray(Charset.forName("koi8"))
        ByteArray(length) {
            if (it == 0) bytes.size.toUByte().toByte()
            else if (it - 1 < bytes.size) bytes[it - 1]
            else 0
        }
    }
    else -> null
}
