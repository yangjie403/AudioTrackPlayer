# Android/Kotlin 文件 IO 与 ByteBuffer 详解

本文用于补充 Android 项目中最常见的文件 IO 知识，并重点解释 `ByteBuffer`。示例以当前项目为背景编写：Kotlin、Android、`minSdk 23`。代码尽量只使用 Android SDK 和 Java/Kotlin 标准库，不依赖第三方文件库。

本文的目标不是背 API，而是建立下面这张“选择路线图”：

```text
我要读写什么？
├─ 少量文本配置       → Context.openFileInput/openFileOutput 或 File + readText/writeText
├─ 普通二进制文件     → InputStream/OutputStream + BufferedInputStream/OutputStream
├─ 大文件顺序读写     → FileInputStream/FileOutputStream + buffer
├─ 需要字符编码       → Reader/Writer，通常再包 BufferedReader/Writer
├─ 需要随机访问/改头部 → RandomAccessFile
├─ 需要 Channel/Buffer → FileChannel + ByteBuffer
├─ 用户选择的文件 Uri  → ContentResolver.openInputStream/openOutputStream
└─ 公共媒体文件       → MediaStore，不直接拼公共存储路径
```

---

## 一、文件 IO 的基本模型

### 1. 文件、字节和字符不是同一层概念

文件 IO 通常分成三层：

| 层次 | 处理对象 | 典型 API | 适用内容 |
|---|---|---|---|
| 文件路径层 | 路径、文件属性、目录 | `File`、`FileDescriptor` | 判断存在、创建目录、重命名、删除 |
| 字节流层 | `ByteArray`、单个字节 | `InputStream`、`OutputStream`、`FileChannel` | 图片、音频、WAV、压缩包、任意二进制 |
| 字符层 | `String`、字符 | `Reader`、`Writer` | 文本、JSON、CSV、日志 |

文本最终也会变成字节。比如 UTF-8 中的中文通常占多个字节，因此不能把一个 UTF-8 文本文件直接当成“每个字节就是一个字符”来处理。

```text
文本："你好"
  ↓ UTF-8 编码
字节：E4 BD A0 E5 A5 BD
  ↓ UTF-8 解码
文本："你好"
```

### 2. IO 操作为什么可能失败

文件 IO 不是纯内存操作，常见失败原因包括：

- 路径不存在或父目录没有创建；
- 没有权限；
- 文件被其他进程占用或正在被替换；
- 存储空间不足；
- 输入数据不完整；
- 编码、字节序或文件格式解释错误；
- 在主线程执行大文件读写导致卡顿或 ANR；
- `InputStream`、`OutputStream`、`Channel` 没有关闭，导致资源泄漏。

因此，文件 IO 代码通常需要同时考虑：资源关闭、异常处理、缓冲、线程、原子性和数据格式。

### 3. 最重要的资源管理规则：使用 `use`

Kotlin 的 `use` 会在代码块结束时自动调用 `close()`，即使代码块中抛出异常也会执行。

```kotlin
val text = File(filesDir, "config.txt").inputStream().use { input ->
    input.bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText()
    }
}
```

通常不要手写：

```kotlin
var input: InputStream? = null
try {
    input = file.inputStream()
    // 读取
} finally {
    input?.close()
}
```

除非你在实现一个底层资源管理类，否则优先使用 `use`。

---

## 二、Android 中的文件位置与访问范围

Android 的“文件路径”首先取决于文件属于谁、由谁选择以及是否需要共享。

### 1. 应用内部存储：`filesDir`

适合保存应用自己的私有文件，例如录音草稿、缓存之外的重要数据、导出的内部 WAV 文件。

```kotlin
val recordingDirectory = File(filesDir, "recordings")
if (!recordingDirectory.exists()) {
    recordingDirectory.mkdirs()
}

val wavFile = File(recordingDirectory, "record_001.wav")
wavFile.writeBytes(ByteArray(0))
```

特点：

- 其他普通应用不能直接访问；
- 不需要存储权限；
- 应用卸载时通常会删除；
- 不适合作为用户长期导出文件的位置。

### 2. 缓存目录：`cacheDir`

```kotlin
val cacheFile = File(cacheDir, "preview.pcm")
```

适合临时文件。系统在空间不足时可能清理缓存，因此不能把唯一的用户数据只放这里。

### 3. 外部应用专属目录：`getExternalFilesDir`

```kotlin
val externalRecordingDir = getExternalFilesDir("recordings")
    ?: error("External files directory is unavailable")

val file = File(externalRecordingDir, "record.wav")
```

它仍然是当前应用专属目录，但通常位于更大的外部存储空间。应用卸载时通常也会删除。不需要申请传统的公共存储写权限。

### 4. 不要随意拼公共存储路径

不要依赖下面这种方式保存用户可见文件：

```kotlin
// 不推荐：不同 Android 版本、厂商和分区存储策略下都可能有问题。
val file = File("/sdcard/Download/record.wav")
```

如果文件需要让用户选择保存位置，使用 Storage Access Framework（SAF）；如果是照片、视频、音频等公共媒体，使用 `MediaStore`。

### 5. 用户选择文件：`Uri` + `ContentResolver`

用户通过系统文件选择器选中一个文件后，你得到的是 `Uri`，不一定能转换成真实路径。应该使用 `ContentResolver` 打开流：

```kotlin
fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Unable to open Uri: $uri" }
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }
}
```

保存到用户选择的位置：

```kotlin
fun writeTextToUri(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri, "wt").use { output ->
        requireNotNull(output) { "Unable to open Uri for writing: $uri" }
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(text)
        }
    }
}
```

`Uri` 可能来自本地文件、云盘、文档提供器或媒体库，所以以下写法不可靠：

```kotlin
// 不可靠：Uri.getPath() 不是普通文件路径。
val file = File(uri.path ?: error("No path"))
```

### 6. SAF 选择文件的完整 Activity 示例

启动系统文件选择器：

```kotlin
private val openDocument = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    if (uri == null) return@registerForActivityResult

    lifecycleScope.launch(Dispatchers.IO) {
        val text = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        withContext(Dispatchers.Main) {
            // 更新 UI
            Log.d("FileIo", "text length=${text.length}")
        }
    }
}

private fun chooseTextFile() {
    openDocument.launch(arrayOf("text/plain", "application/json"))
}
```

创建保存目标：

```kotlin
private val createDocument = registerForActivityResult(
    ActivityResultContracts.CreateDocument("audio/wav")
) { uri: Uri? ->
    if (uri == null) return@registerForActivityResult

    lifecycleScope.launch(Dispatchers.IO) {
        contentResolver.openOutputStream(uri).use { output ->
            requireNotNull(output)
            output.write(byteArrayOf(/* WAV 内容 */))
        }
    }
}

private fun chooseSaveLocation() {
    createDocument.launch("my_recording.wav")
}
```

如果项目没有使用 Activity Result API，也可以使用旧的 `startActivityForResult`，但新代码更推荐上面的方式。

---

## 三、`java.io.File`：路径和文件管理 API

`File` 本身不负责高效读写内容，它主要表示一个路径，并提供文件属性和基本文件操作。

### 1. 创建文件和目录

```kotlin
val root = File(filesDir, "demo")

// 创建多级目录。成功创建或目录已存在时返回 true/false，不能只看返回值判断最终状态。
check(root.exists() || root.mkdirs()) {
    "Unable to create directory: $root"
}

val file = File(root, "hello.txt")
if (!file.exists()) {
    check(file.createNewFile()) { "Unable to create file: $file" }
}
```

`mkdir()` 只创建最后一级目录；`mkdirs()` 会创建不存在的父目录：

```kotlin
File(filesDir, "a/b/c").mkdir()  // 如果 a/b 不存在，通常失败
File(filesDir, "a/b/c").mkdirs() // 尝试创建 a、a/b、a/b/c
```

### 2. 常用属性

```kotlin
fun inspectFile(file: File) {
    println("path=${file.absolutePath}")
    println("name=${file.name}")
    println("parent=${file.parent}")
    println("exists=${file.exists()}")
    println("isFile=${file.isFile}")
    println("isDirectory=${file.isDirectory}")
    println("length=${file.length()} bytes")
    println("lastModified=${file.lastModified()}")
    println("canRead=${file.canRead()}")
    println("canWrite=${file.canWrite()}")
}
```

注意：`length()` 对目录没有通常意义；文件不存在时 `length()` 会返回 0，不能单独用它判断文件是否存在。

### 3. 列出目录

```kotlin
val children: Array<File> = root.listFiles().orEmpty()
for (child in children) {
    println("${if (child.isDirectory) "DIR " else "FILE"} ${child.name}")
}
```

带过滤条件：

```kotlin
val wavFiles = root.listFiles { file ->
    file.isFile && file.extension.equals("wav", ignoreCase = true)
}.orEmpty()
```

`listFiles()` 可能返回 `null`，例如路径不是目录、目录不存在或发生 IO 错误。`orEmpty()` 可以安全转换为空数组，但如果业务必须区分“空目录”和“读取失败”，应显式检查。

### 4. 复制、移动和删除

简单小文件可以使用 Kotlin 扩展：

```kotlin
sourceFile.copyTo(targetFile, overwrite = true)
sourceFile.copyTo(targetFile, overwrite = false)
sourceFile.renameTo(targetFile)
sourceFile.delete()
```

`renameTo()` 返回 Boolean，失败时信息较少。跨文件系统移动、需要覆盖、需要明确异常时更推荐 `java.nio.file.Files.move`，但 Android API 兼容性需要额外处理。普通 Android 项目可以自己用流复制：

```kotlin
fun copyFile(source: File, target: File) {
    target.parentFile?.mkdirs()
    source.inputStream().use { input ->
        target.outputStream().use { output ->
            input.copyTo(output, bufferSize = 64 * 1024)
        }
    }
}
```

递归删除目录：

```kotlin
fun deleteRecursivelySafely(directory: File) {
    require(directory.isDirectory) { "Expected directory: $directory" }

    directory.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory) {
            deleteRecursivelySafely(child)
        } else {
            check(child.delete()) { "Unable to delete file: $child" }
        }
    }
    check(directory.delete()) { "Unable to delete directory: $directory" }
}
```

删除操作具有破坏性。真实业务中应先确认目标路径，避免把不受控的路径直接递归删除。

### 5. 原子替换配置文件

直接覆盖配置文件时，如果进程在写入过程中崩溃，可能留下半个 JSON。可以先写临时文件，写完并关闭后再替换：

```kotlin
fun writeTextAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.tmp")

    temp.outputStream().use { output ->
        // 这里先直接写 UTF-8 字节，再在关闭前请求刷入存储设备。
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
        output.fd.sync()
    }

    if (target.exists() && !target.delete()) {
        error("Unable to replace old file: $target")
    }
    check(temp.renameTo(target)) { "Unable to rename temporary file" }
}
```

上面示例适用于应用私有目录中的普通文件。对于需要更强崩溃一致性的场景，还需要考虑目录项同步、文件系统行为和并发写入。

---

## 四、字节流：`InputStream` 与 `OutputStream`

### 1. 单字节读写为什么不适合大文件

最基础的 API 是：

```kotlin
val oneByte: Int = input.read()
output.write(oneByte)
```

`read()` 返回 `Int` 而不是 `Byte`，因为 `-1` 用来表示 EOF（文件结束）。有效字节范围是 `0..255`。

```kotlin
while (true) {
    val value = input.read()
    if (value == -1) break
    // value 是 0..255
}
```

逐字节调用方法会产生大量方法调用，适合教学或极小文件，不适合复制大文件。

### 2. 使用 `ByteArray` 缓冲复制

```kotlin
fun copyStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(16 * 1024)

    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        output.write(buffer, 0, count)
    }
}
```

完整文件复制：

```kotlin
fun copyFileWithStream(source: File, target: File) {
    target.parentFile?.mkdirs()

    source.inputStream().use { input ->
        target.outputStream().use { output ->
            copyStream(input, output)
            output.flush()
        }
    }
}
```

`read(buffer)` 返回的数量可能小于 `buffer.size`，也可能为 0（某些特殊流），不能假设一次就填满缓冲区。必须使用返回的 `count`，不能总是写出整个 buffer。

错误示例：

```kotlin
val buffer = ByteArray(4096)
val count = input.read(buffer)
output.write(buffer) // 错误：count 小于 4096 时会多写旧数据或无效数据
```

### 3. `BufferedInputStream` 与 `BufferedOutputStream`

它们在流外面增加缓冲，减少底层系统调用：

```kotlin
FileInputStream(source).use { rawInput ->
    BufferedInputStream(rawInput, 32 * 1024).use { input ->
        FileOutputStream(target).use { rawOutput ->
            BufferedOutputStream(rawOutput, 32 * 1024).use { output ->
                input.copyTo(output)
            }
        }
    }
}
```

Kotlin 的 `copyTo()` 本身已经使用缓冲区，所以常见场景直接写下面这样即可：

```kotlin
source.inputStream().use { input ->
    target.outputStream().use { output ->
        input.copyTo(output, bufferSize = 32 * 1024)
    }
}
```

### 4. `available()` 不是文件总长度

`InputStream.available()` 表示“不阻塞就能读到的字节数”，不是整个输入的长度。尤其是网络流、管道流和 `ContentResolver` 流，不能用它计算文件大小：

```kotlin
// 不要这样判断文件长度
val size = input.available()
```

如果是 `File`，使用 `file.length()`；如果是 `Uri`，可以通过 `ContentResolver.query()` 查询 `OpenableColumns.SIZE`，但该字段也可能为空。

### 5. `mark` 与 `reset`

部分输入流支持临时回退：

```kotlin
BufferedInputStream(file.inputStream()).use { input ->
    if (input.markSupported()) {
        input.mark(1024)
        val first = input.read()
        input.reset()
        val sameFirst = input.read()
        check(first == sameFirst)
    }
}
```

`mark(readLimit)` 只保证在指定范围内可回退，不能把它当成通用随机访问。需要任意位置访问时使用 `RandomAccessFile` 或 `FileChannel`。

---

## 五、字符流：`Reader` 与 `Writer`

### 1. 文本文件必须明确字符集

推荐显式指定 UTF-8：

```kotlin
val text = file.readText(Charsets.UTF_8)
file.writeText("录音配置", Charsets.UTF_8)
```

流式版本：

```kotlin
FileInputStream(file).use { input ->
    InputStreamReader(input, Charsets.UTF_8).use { reader ->
        BufferedReader(reader).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                println(line)
            }
        }
    }
}
```

写文本：

```kotlin
FileOutputStream(file).use { output ->
    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
        BufferedWriter(writer).use { buffered ->
            buffered.appendLine("sampleRate=44100")
            buffered.appendLine("channels=1")
        }
    }
}
```

### 2. `readLine()` 的资源问题

不要忘记关闭 `BufferedReader`：

```kotlin
val lines = file.bufferedReader(Charsets.UTF_8).use { reader ->
    reader.readLines()
}
```

大文件不建议 `readLines()`，因为它会把所有行一次性放入内存。使用 `useLines`：

```kotlin
file.bufferedReader(Charsets.UTF_8).useLines { lines ->
    lines.forEach { line ->
        processLine(line)
    }
}
```

### 3. 文本追加与覆盖

```kotlin
file.writeText("first\n", Charsets.UTF_8)
file.appendText("second\n", Charsets.UTF_8)
```

流式追加：

```kotlin
file.outputStream(append = true).use { output ->
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine("new log entry")
    }
}
```

### 4. 不要用文本 API 处理二进制

下面的写法会破坏图片、音频和 WAV：

```kotlin
// 错误：二进制内容不应该经过字符集解码和编码
val text = binaryFile.readText(Charsets.UTF_8)
binaryFile.writeText(text, Charsets.UTF_8)
```

二进制应该使用 `ByteArray`、`InputStream`、`OutputStream` 或 `ByteBuffer`。

---

## 六、Android 的 `Context.openFileInput/openFileOutput`

应用内部小型文本或二进制文件可以直接用 Context API：

```kotlin
fun saveSettings(context: Context, content: String) {
    context.openFileOutput("settings.txt", Context.MODE_PRIVATE).use { output ->
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
        }
    }
}

fun loadSettings(context: Context): String? {
    return try {
        context.openFileInput("settings.txt").use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    } catch (_: FileNotFoundException) {
        null
    }
}
```

`MODE_PRIVATE` 的含义是覆盖同名文件，而不是“让文件对其他应用私有”。应用内部目录本来就是应用私有的。

追加模式：

```kotlin
context.openFileOutput(
    "events.log",
    Context.MODE_PRIVATE or Context.MODE_APPEND
).use { output ->
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine("event=${System.currentTimeMillis()}")
    }
}
```

如果需要列出 Context 内部文件：

```kotlin
context.fileList().forEach { name ->
    Log.d("FileIo", "internal file=$name")
}
```

---

## 七、`RandomAccessFile`：随机访问与回填文件头

### 1. 基本概念

`RandomAccessFile` 同时支持读和写，并维护一个文件指针。常用方法：

| API | 作用 |
|---|---|
| `seek(position)` | 把文件指针移动到指定字节位置 |
| `getFilePointer()` | 获取当前指针位置 |
| `length()` | 获取文件长度 |
| `setLength(length)` | 截断或扩展文件 |
| `read()` / `readFully()` | 读取数据 |
| `write()` / `writeInt()` | 写入数据 |
| `readInt()` / `writeInt()` | 按 Java 大端序读写整数 |

特别注意：`RandomAccessFile.writeInt()` 使用 big-endian，而 WAV 需要 little-endian。二进制协议必须确认字节序，不能盲目使用 `writeInt()`。

### 2. 修改文件中的整数

```kotlin
fun writeLittleEndianIntAt(file: File, offset: Long, value: Int) {
    RandomAccessFile(file, "rw").use { random ->
        random.seek(offset)
        random.write(value and 0xff)
        random.write((value ushr 8) and 0xff)
        random.write((value ushr 16) and 0xff)
        random.write((value ushr 24) and 0xff)
    }
}
```

### 3. 读取固定长度记录

```kotlin
data class Record(val id: Int, val value: Long)

fun readRecord(file: File, index: Long): Record {
    val recordSize = 12L
    RandomAccessFile(file, "r").use { random ->
        random.seek(index * recordSize)
        val id = random.readInt()       // Java 大端序示例
        val value = random.readLong()    // Java 大端序示例
        return Record(id, value)
    }
}
```

固定长度记录可以通过 `index * recordSize` O(1) 定位，不需要从文件开头顺序读到目标位置。

### 4. WAV 头回填示例

录音时先写 44 字节占位内容：

```kotlin
RandomAccessFile(wavFile, "rw").use { random ->
    random.write(ByteArray(44))
    // 后续追加 PCM
    random.write(pcmBytes)

    val dataSize = pcmBytes.size.toLong()
    random.seek(4L)
    writeUInt32LittleEndian(random, 36L + dataSize)

    random.seek(40L)
    writeUInt32LittleEndian(random, dataSize)
}
```

实际项目中应把文件头字段全部写完整，并对 `dataSize` 做 32-bit WAV 的大小限制；完整的 `WavFileWriter` 可参考同目录的《录音功能实现方案》文档。

---

## 八、ByteBuffer 的核心概念

`ByteBuffer` 是 Java NIO 中用于读写字节的缓冲区。它不是普通的 `ByteArray`，而是带有位置状态和类型读写能力的“字节容器”。它经常与 `FileChannel`、Socket Channel、音视频数据、二进制协议配合。

### 1. 四个状态字段

每个 `ByteBuffer` 主要有四个状态：

| 字段 | 含义 |
|---|---|
| `capacity` | 总容量，创建后固定 |
| `position` | 下一次读或写发生的位置 |
| `limit` | 当前允许读写的边界 |
| `mark` | 可选的临时标记位置 |

必须满足：

```text
0 <= mark <= position <= limit <= capacity
```

可以把它想成一排座位：

```text
capacity = 10

写入后：
[A][B][C][D][E][ ][ ][ ][ ][ ]
 ^           ^                   ^
 0         position            capacity
             limit 默认等于 capacity
```

### 2. 创建 Heap ByteBuffer

```kotlin
val buffer = ByteBuffer.allocate(16)

println(buffer.capacity()) // 16
println(buffer.position()) // 0
println(buffer.limit())    // 16
```

`allocate()` 创建 JVM/Android 堆上的缓冲区。它有一个可访问的 backing array：

```kotlin
val buffer = ByteBuffer.allocate(4)
buffer.put(1)
buffer.put(2)

val array: ByteArray = buffer.array()
println(array.contentToString())
```

### 3. 创建 Direct ByteBuffer

```kotlin
val direct = ByteBuffer.allocateDirect(4096)
println(direct.isDirect) // true
```

Direct buffer 的内存不在普通 JVM/Android 堆中，某些底层 IO 操作可以减少一次数据复制。代价是：

- 分配和释放成本通常更高；
- 不能调用 `array()`；
- 不应该为每次小 IO 临时创建；
- 仍然需要正确限制大小，避免占用过多 native memory。

文件读写中，如果使用 `FileChannel.read/write(ByteBuffer)`，direct buffer 有时更有利；普通小文件使用 `ByteArray` 或 heap buffer 通常已经足够。

### 4. `position`、`limit` 和 `remaining`

```kotlin
val buffer = ByteBuffer.allocate(8)
buffer.put(10)
buffer.put(20)
buffer.put(30)

println(buffer.position())  // 3
println(buffer.limit())     // 8
println(buffer.remaining()) // 5，可继续写入的空间
```

`remaining()` 始终是 `limit - position`。它的含义取决于当前模式：

- 写模式：还能写多少字节；
- 读模式：还能读多少字节。

---

## 九、ByteBuffer 的写入与读取

### 1. 写入基本类型

```kotlin
val buffer = ByteBuffer.allocate(16)

buffer.put(0x7F.toByte())
buffer.putShort(1234.toShort())
buffer.putInt(0x12345678)
buffer.putLong(999L)

println(buffer.position()) // 15
```

`putShort`、`putInt`、`putLong` 会按照当前 `ByteOrder` 把多字节类型拆成多个字节。

### 2. 读取前必须 `flip()`

写入完成后，`position` 在数据末尾；读取应该从数据开头开始。`flip()` 会执行：

```text
limit = position
position = 0
```

示例：

```kotlin
val buffer = ByteBuffer.allocate(16)
buffer.putInt(123)
buffer.putShort(7.toShort())

buffer.flip()

val number = buffer.int
val smallNumber = buffer.short
println(number)      // 123
println(smallNumber) // 7
println(buffer.remaining()) // 0
```

最常见的错误就是忘记 `flip()`：

```kotlin
val buffer = ByteBuffer.allocate(4)
buffer.putInt(123)

// 错误：position 在 4，直接读取会认为没有可读数据，或抛 BufferUnderflowException
// val value = buffer.int
```

### 3. `clear()`：准备重新写入

`clear()` 设置：

```text
position = 0
limit = capacity
```

它不会擦除旧数据，只是把缓冲区视为“可重新写入”：

```kotlin
buffer.clear()
val count = input.read(buffer.array())
buffer.limit(count)
buffer.position(0)
```

对 Channel 更常见的是：

```kotlin
buffer.clear()
val bytesRead = channel.read(buffer)
buffer.flip()
while (buffer.hasRemaining()) {
    output.write(buffer.get())
}
```

### 4. `rewind()`：重新从头读同一批数据

`rewind()` 设置 `position = 0`，但保留当前 `limit`：

```kotlin
buffer.flip()
val first = buffer.get()
buffer.rewind()
val sameFirst = buffer.get()
check(first == sameFirst)
```

适合重复读取已经准备好的数据，不适合把缓冲区切回写模式。

### 5. `compact()`：保留未处理的数据

网络协议和流式解析中，一次 read 可能读到半个消息。处理完前半段后，未处理的尾部需要移到开头，再继续读：

```kotlin
buffer.flip() // 切换到读模式

// 假设只处理了前 3 字节，剩余内容仍然要保留
buffer.position(3)
buffer.compact()
// 未处理内容已经移动到开头；position 指向它们后面

channel.read(buffer) // 从剩余数据后面继续写
```

`compact()` 的状态效果大致是：

```text
旧： [已处理][未处理][空闲]
新： [未处理][空闲      ]
position = 未处理末尾
limit = capacity
```

### 6. 四个方法的选择口诀

```text
写完准备读        → flip()
读完准备重新写    → clear()
从头再读一遍      → rewind()
保留未处理尾部    → compact()
```

### 7. `hasRemaining()` 是安全遍历方式

```kotlin
buffer.flip()
while (buffer.hasRemaining()) {
    val value = buffer.get()
    processByte(value)
}
```

不要无条件读取超过 `limit`，否则会抛出 `BufferUnderflowException`。写入时也不能超过 `capacity` 或当前 `limit`，否则会抛出 `BufferOverflowException`。

---

## 十、ByteOrder：大小端字节序

### 1. 大端与小端

整数 `0x12345678` 拆成四个字节后：

```text
Big-endian（大端）：12 34 56 78
Little-endian（小端）：78 56 34 12
```

WAV、Windows 位图和许多硬件/协议格式使用 little-endian；网络字节序通常使用 big-endian。

### 2. 设置 ByteBuffer 的字节序

```kotlin
val littleEndian = ByteBuffer
    .allocate(4)
    .order(ByteOrder.LITTLE_ENDIAN)

littleEndian.putInt(0x12345678)
littleEndian.flip()

println(littleEndian.get().toUByte().toString(16)) // 78
```

读取也必须使用相同字节序：

```kotlin
val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
val value = ByteBuffer
    .wrap(bytes)
    .order(ByteOrder.LITTLE_ENDIAN)
    .int

check(value == 0x12345678)
```

### 3. `nativeOrder()` 不等于文件格式字节序

```kotlin
val native = ByteOrder.nativeOrder()
```

`nativeOrder()` 是当前 CPU 的本机字节序，适合和本机底层 API 配合，但不能用来猜文件格式。文件格式应遵守格式规范：WAV 明确是 little-endian，就显式使用 `LITTLE_ENDIAN`。

### 4. Android 音频 PCM 的例子

PCM 16-bit little-endian 解码：

```kotlin
fun decodePcm16LittleEndian(bytes: ByteArray): ShortArray {
    require(bytes.size % 2 == 0) { "PCM 16-bit data must have even byte count" }

    val input = ByteBuffer
        .wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)

    val samples = ShortArray(bytes.size / 2)
    input.asShortBuffer().get(samples)
    return samples
}
```

如果是一个 `ByteBuffer`，不要默认它已经是正确字节序；在 `getShort()` 前先设置 `.order(ByteOrder.LITTLE_ENDIAN)`。

---

## 十一、ByteBuffer 的索引读写与 bulk 操作

### 1. 相对读写与绝对读写

相对读写会移动 `position`：

```kotlin
buffer.putInt(100)
val value = buffer.getInt()
```

绝对读写指定索引，不改变 `position`：

```kotlin
val buffer = ByteBuffer.allocate(8)
buffer.putInt(0, 100)
buffer.putInt(4, 200)

check(buffer.position() == 0)
check(buffer.getInt(0) == 100)
check(buffer.getInt(4) == 200)
```

绝对方法仍然受 `limit` 限制，越界会抛异常。

### 2. 批量读写 `ByteArray`

```kotlin
val source = byteArrayOf(1, 2, 3, 4)
val buffer = ByteBuffer.allocate(8)

buffer.put(source)
buffer.put(byteArrayOf(9, 9), 0, 2)

buffer.flip()
val target = ByteArray(buffer.remaining())
buffer.get(target)
println(target.contentToString()) // [1, 2, 3, 4, 9, 9]
```

批量操作通常比逐字节调用更清晰，也更高效。

### 3. `asShortBuffer`、`asIntBuffer`

可以把同一块字节内存视为其他基本类型数组：

```kotlin
val byteBuffer = ByteBuffer
    .allocate(8)
    .order(ByteOrder.LITTLE_ENDIAN)

val shortBuffer = byteBuffer.asShortBuffer()
shortBuffer.put(100.toShort())
shortBuffer.put((-100).toShort())

shortBuffer.flip()
println(shortBuffer.get())
println(shortBuffer.get())
```

注意：`ShortBuffer` 和原 `ByteBuffer` 共享底层数据，但它们有各自的 `position/limit/capacity`。改变一个 Buffer 的 position 不会自动改变另一个。

---

## 十二、`slice`、`duplicate` 和只读 Buffer

### 1. `slice()`：创建共享数据的子视图

```kotlin
val source = ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 50))
source.position(1)
source.limit(4)

val slice = source.slice()
println(slice.remaining()) // 3，对应原 buffer 的 20、30、40

slice.put(0, 99)
println(source.get(1)) // 99，共享同一块底层数据
```

新版本 Java/Android 还提供 `slice(index, length)`，但如果需要兼容较低 API，应使用 `position/limit/slice` 组合或检查 API。

### 2. `duplicate()`：共享数据，独立状态

```kotlin
val original = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
val copyView = original.duplicate()

copyView.get()
check(original.position() == 0) // position 独立

copyView.put(0, 99)
check(original.get(0) == 99) // 内容共享
```

`duplicate()` 很适合需要两个不同读取游标、但不想复制底层数据的场景。

### 3. `asReadOnlyBuffer()`

```kotlin
val writable = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
val readOnly = writable.asReadOnlyBuffer()

println(readOnly.isReadOnly) // true
println(readOnly.get())
// readOnly.put(9) 会抛 ReadOnlyBufferException
```

只读 View 仍然能看到原始可写 Buffer 对数据的修改；它主要是防止通过该 View 修改，而不是复制数据。

### 4. `array()` 的注意事项

```kotlin
val heap = ByteBuffer.allocate(4)
val bytes = heap.array()

val direct = ByteBuffer.allocateDirect(4)
// direct.array() 会抛 UnsupportedOperationException

val readOnly = heap.asReadOnlyBuffer()
// readOnly.array() 可能抛 ReadOnlyBufferException
```

如果代码必须支持 heap、direct、read-only 三种 Buffer，不要依赖 `array()`，使用 `get(byteArray)` 或 Channel API。

---

## 十三、ByteBuffer 与 FileChannel

### 1. Channel 与 Stream 的区别

Stream 更像“从头到尾读写”；Channel 可以结合 Buffer，并支持位置读写、`transferTo/transferFrom` 等能力。

```text
FileChannel.read(buffer)
    文件 → Buffer

buffer.flip()

FileChannel.write(buffer)
    Buffer → 文件
```

### 2. 顺序写入文件

```kotlin
fun writeBytesWithChannel(file: File, data: ByteArray) {
    file.parentFile?.mkdirs()

    FileOutputStream(file).use { output ->
        val channel = output.channel
        val buffer = ByteBuffer.wrap(data)

        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }
}
```

`FileChannel.write(buffer)` 也可能只写入部分内容，所以需要 `while (buffer.hasRemaining())`，不能假设一次写完。

### 3. 顺序读取文件

```kotlin
fun readAllWithChannel(file: File): ByteArray {
    require(file.length() <= Int.MAX_VALUE) { "File is too large for ByteArray" }

    FileInputStream(file).use { input ->
        val channel = input.channel
        val buffer = ByteBuffer.allocate(file.length().toInt())

        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count == -1) break
        }

        buffer.flip()
        val result = ByteArray(buffer.remaining())
        buffer.get(result)
        return result
    }
}
```

大文件不要这样一次性分配全部大小，应使用固定大小 Buffer：

```kotlin
fun copyWithChannel(source: File, target: File) {
    target.parentFile?.mkdirs()

    FileInputStream(source).use { input ->
        FileOutputStream(target).use { output ->
            val inChannel = input.channel
            val outChannel = output.channel
            val buffer = ByteBuffer.allocateDirect(64 * 1024)

            while (true) {
                buffer.clear()
                val count = inChannel.read(buffer)
                if (count == -1) break

                buffer.flip()
                while (buffer.hasRemaining()) {
                    outChannel.write(buffer)
                }
            }
        }
    }
}
```

### 4. 使用 `transferTo` 复制文件

```kotlin
fun copyWithTransferTo(source: File, target: File) {
    target.parentFile?.mkdirs()

    FileInputStream(source).use { input ->
        FileOutputStream(target).use { output ->
            val sourceChannel = input.channel
            val targetChannel = output.channel
            var position = 0L
            val size = sourceChannel.size()

            while (position < size) {
                val transferred = sourceChannel.transferTo(
                    position,
                    size - position,
                    targetChannel
                )
                check(transferred > 0L) { "transferTo made no progress" }
                position += transferred
            }
        }
    }
}
```

即使请求传输整个文件，`transferTo` 也可能一次只传输一部分，所以必须循环。

### 5. 随机位置读写 `FileChannel`

```kotlin
fun readAt(file: File, position: Long, size: Int): ByteArray {
    val result = ByteArray(size)
    FileInputStream(file).use { input ->
        val channel = input.channel
        val buffer = ByteBuffer.wrap(result)
        var filePosition = position

        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, filePosition)
            if (count == -1) break
            filePosition += count
        }
    }
    return result
}
```

`FileChannel.read(buffer, position)` 是 position-based read，不会改变 Channel 自身的共享 position，适合多个线程读取不同文件区域；但仍要自行设计线程安全和文件生命周期。

### 6. `force()`：请求刷入存储设备

```kotlin
FileOutputStream(file).use { output ->
    output.channel.use { channel ->
        val buffer = ByteBuffer.wrap("important".toByteArray())
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}
```

`force(true)` 请求把文件内容和元数据刷入存储设备，但不同文件系统和设备对持久化保证仍有差异。普通日志不一定需要每次调用；重要状态文件可以结合临时文件和原子替换使用。

---

## 十四、用 ByteBuffer 解析二进制文件头

### 1. 通用的 little-endian 读取工具

```kotlin
object BinaryReader {
    fun readUInt16(buffer: ByteBuffer): Int {
        return buffer.short.toInt() and 0xFFFF
    }

    fun readUInt32(buffer: ByteBuffer): Long {
        return buffer.int.toLong() and 0xFFFF_FFFFL
    }

    fun readFourCc(buffer: ByteBuffer): String {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
```

使用前设置字节序：

```kotlin
val header = ByteBuffer
    .wrap(bytes)
    .order(ByteOrder.LITTLE_ENDIAN)

val riff = BinaryReader.readFourCc(header)
val riffSize = BinaryReader.readUInt32(header)
val wave = BinaryReader.readFourCc(header)

check(riff == "RIFF")
check(wave == "WAVE")
println("RIFF size=$riffSize")
```

### 2. 解析固定的 WAV 44 字节头

下面是教学版代码，适用于 PCM WAV 的经典 44 字节头；真实 WAV 可能存在额外 chunk，应像录音文档中的播放器那样按 chunk 遍历。

```kotlin
data class SimpleWavHeader(
    val channelCount: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataSize: Long
)

fun parseClassicWavHeader(file: File): SimpleWavHeader {
    require(file.length() >= 44L) { "WAV file is too short" }

    val bytes = FileInputStream(file).use { input ->
        ByteArray(44).also { readExactly(input, it) }
    }

    val buffer = ByteBuffer
        .wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)

    val riff = ByteArray(4).also { buffer.get(it) }
    require(String(riff, Charsets.US_ASCII) == "RIFF")

    buffer.int // RIFF size

    val wave = ByteArray(4).also { buffer.get(it) }
    require(String(wave, Charsets.US_ASCII) == "WAVE")

    val fmt = ByteArray(4).also { buffer.get(it) }
    require(String(fmt, Charsets.US_ASCII) == "fmt ")

    val fmtSize = buffer.int
    require(fmtSize == 16) { "This classic example expects a 16-byte fmt chunk" }

    val audioFormat = buffer.short.toInt() and 0xFFFF
    require(audioFormat == 1) { "Only PCM is supported" }

    val channels = buffer.short.toInt() and 0xFFFF
    val sampleRate = buffer.int
    buffer.int // byteRate
    buffer.short // blockAlign
    val bitsPerSample = buffer.short.toInt() and 0xFFFF

    val data = ByteArray(4).also { buffer.get(it) }
    require(String(data, Charsets.US_ASCII) == "data")
    val dataSize = buffer.int.toLong() and 0xFFFF_FFFFL

    return SimpleWavHeader(channels, sampleRate, bitsPerSample, dataSize)
}
```

上面的 `readExactly` 会循环读取，直到目标数组填满或明确遇到 EOF。`InputStream.read(byteArray)` 一次不一定填满整个数组，因此不能只调用一次就认为 44 字节已经读完。

```kotlin
fun readExactly(input: InputStream, destination: ByteArray) {
    var offset = 0
    while (offset < destination.size) {
        val count = input.read(destination, offset, destination.size - offset)
        if (count == -1) error("Unexpected end of file")
        offset += count
    }
}
```

调用时：

```kotlin
FileInputStream(file).use { input ->
    val bytes = ByteArray(44)
    readExactly(input, bytes)
    // 用 ByteBuffer 解析 bytes
}
```

### 3. 解析可变 chunk 的原则

不能假设每个 WAV 都是：

```text
RIFF → fmt → data
```

可能还有 `LIST`、`JUNK`、`fact` 等块。通用算法是：

```kotlin
while (还有 chunk 头) {
    val id = readFourCc()
    val size = readUInt32LittleEndian()
    val dataStart = currentPosition

    when (id) {
        "fmt " -> 解析格式
        "data" -> 记录 dataStart 和 size
        else -> 跳过
    }

    seek(dataStart + size + (size and 1)) // RIFF chunk 偶数字节对齐
}
```

解析二进制文件时，`ByteBuffer` 负责解释字节，`FileChannel`/`RandomAccessFile` 负责定位和读取，两者职责要分开。

---

## 十五、ByteBuffer 实现“半包/粘包”解析

网络或文件流常常不是一次读到完整消息。假设协议格式是：

```text
4 字节 little-endian bodyLength
bodyLength 个 body 字节
```

可以使用 `compact()` 保留未完成消息：

```kotlin
class LengthPrefixedParser(
    private val maxBodySize: Int = 1024 * 1024
) {
    private val buffer = ByteBuffer
        .allocate(64 * 1024)
        .order(ByteOrder.LITTLE_ENDIAN)

    fun onBytes(input: ByteArray, onMessage: (ByteArray) -> Unit) {
        require(input.size <= buffer.remaining()) {
            "Input is larger than remaining buffer space"
        }
        buffer.put(input)
        buffer.flip()

        while (true) {
            if (buffer.remaining() < 4) break

            buffer.mark()
            val bodyLength = buffer.int
            require(bodyLength in 0..maxBodySize) {
                "Invalid body length: $bodyLength"
            }

            if (buffer.remaining() < bodyLength) {
                buffer.reset()
                break
            }

            val body = ByteArray(bodyLength)
            buffer.get(body)
            onMessage(body)
        }

        buffer.compact()
    }
}
```

这段代码的关键顺序是：

1. `put()` 追加新字节；
2. `flip()` 进入读取已有字节的模式；
3. 不够一条完整消息时 `reset()` 回到消息头；
4. `compact()` 把未完成消息移到开头；
5. 下次 `put()` 从未处理数据后面继续写。

这就是 `ByteBuffer` 在增量解析器中的典型用法。

---

## 十六、ByteBuffer 常见错误

### 错误 1：写完不 `flip`

```kotlin
val buffer = ByteBuffer.allocate(10)
buffer.put(byteArrayOf(1, 2, 3))

// 错误：position=3，直接从 position 处读，读不到刚才的数据
```

修复：

```kotlin
buffer.flip()
```

### 错误 2：读取后用 `clear`，导致未处理数据丢失

```kotlin
buffer.flip()
processSomeButNotAll(buffer)
buffer.clear() // 错误：还没处理的尾部被逻辑上丢弃
```

如果还有未处理数据，使用：

```kotlin
buffer.compact()
```

### 错误 3：把 `capacity` 当成可读数据长度

```kotlin
val buffer = ByteBuffer.allocate(1024)
channel.read(buffer)
buffer.flip()

// 错误：应该使用 remaining，而不是 capacity
val bytes = ByteArray(buffer.capacity())
```

修复：

```kotlin
val bytes = ByteArray(buffer.remaining())
buffer.get(bytes)
```

### 错误 4：假设 `read` 或 `write` 一次完成

```kotlin
channel.write(buffer) // 可能只写一部分
```

修复：

```kotlin
while (buffer.hasRemaining()) {
    channel.write(buffer)
}
```

### 错误 5：直接调用 `array()`

Direct Buffer 和只读 Buffer 可能没有可访问数组。使用：

```kotlin
val bytes = ByteArray(buffer.remaining())
buffer.get(bytes)
```

### 错误 6：忽略字节序

```kotlin
val value = buffer.int // 默认 ByteOrder 通常是 BIG_ENDIAN
```

如果文件规范是 little-endian：

```kotlin
buffer.order(ByteOrder.LITTLE_ENDIAN)
val value = buffer.int
```

### 错误 7：复用 Buffer 时没有确认模式

每次复用前先明确目标：

```kotlin
buffer.clear() // 我要覆盖写入
channel.read(buffer)
buffer.flip()  // 我要读取刚写入的内容
```

不要仅凭“上次操作是什么”猜当前 position/limit。

---

## 十七、后台线程与协程

大多数文件 IO 都不应该在主线程执行，尤其是：

- 大文件复制；
- 读取 WAV/MP3/PCM；
- 解析大量文本；
- `FileChannel.force()`；
- 访问云盘等 `ContentResolver` Provider。

使用 Kotlin Coroutines 时，可以把阻塞 IO 放到 `Dispatchers.IO`：

下面的协程示例需要项目已有 `kotlinx-coroutines-android` 和 `lifecycle-runtime-ktx`（提供
`lifecycleScope`）。当前项目如果尚未引入这两个依赖，可以先使用本节后面的
`ExecutorService` 示例，或者按项目的依赖管理方式加入它们。

```kotlin
suspend fun loadFileText(file: File): String = withContext(Dispatchers.IO) {
    file.readText(Charsets.UTF_8)
}

suspend fun copyFileAsync(source: File, target: File) = withContext(Dispatchers.IO) {
    target.parentFile?.mkdirs()
    source.inputStream().use { input ->
        target.outputStream().use { output ->
            input.copyTo(output, 64 * 1024)
        }
    }
}
```

在 Activity 中：

```kotlin
lifecycleScope.launch {
    try {
        val text = withContext(Dispatchers.IO) {
            file.readText(Charsets.UTF_8)
        }
        textView.text = text
    } catch (error: IOException) {
        textView.text = "读取失败：${error.message}"
    }
}
```

不要为了“切换线程”而在每次循环中创建一个协程；通常一次 IO 任务使用一个协程，在内部复用 Buffer。

如果项目不使用协程，也可以使用线程池：

```kotlin
private val ioExecutor = Executors.newSingleThreadExecutor()

fun loadTextAsync(file: File, onResult: (Result<String>) -> Unit) {
    ioExecutor.execute {
        val result = runCatching { file.readText(Charsets.UTF_8) }
        Handler(Looper.getMainLooper()).post {
            onResult(result)
        }
    }
}
```

Activity 销毁时应取消协程或关闭线程池，避免回调已经失效的 UI。

---

## 十八、读写大文件的内存和性能建议

### 1. 不要无条件 `readBytes()`

```kotlin
// 小配置可以，大文件可能导致内存压力
val all = file.readBytes()
```

对音频、视频、日志、压缩包等大文件，使用固定大小 Buffer 分块处理。

### 2. Buffer 大小不是越大越好

常见起点：

- 文本/普通文件：8 KiB～64 KiB；
- 音频 PCM：按约 10～100 ms 的音频量选择；
- Channel 文件复制：32 KiB～256 KiB；
- Direct Buffer：长期复用，不要循环内反复分配。

最终应以真实设备测试为准。

### 3. 复用 ByteArray/ByteBuffer

```kotlin
val buffer = ByteArray(64 * 1024)
repeat(fileList.size) { index ->
    input.read(buffer)
    // 复用同一个数组
}
```

避免每次循环创建 `ByteArray`，否则会增加 GC 压力。

### 4. 用进度回调，但不要每个字节回调

```kotlin
fun copyWithProgress(
    source: File,
    target: File,
    onProgress: (Long, Long) -> Unit
) {
    val total = source.length()
    var copied = 0L
    val buffer = ByteArray(64 * 1024)

    source.inputStream().use { input ->
        target.outputStream().use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                copied += count
                onProgress(copied, total)
            }
        }
    }
}
```

真实 UI 中可以按时间间隔或百分比变化节流回调，避免频繁刷新。

---

## 十九、权限、错误处理和安全性

### 1. 应用私有目录一般不需要存储权限

`filesDir`、`cacheDir`、`getExternalFilesDir()` 通常不需要传统存储权限。麦克风权限是录音功能的权限，不是文件 IO 权限。

### 2. 使用公共媒体库需要正确 API

如果要把 WAV 发布到用户的公共音乐目录，可以使用 `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` 插入 `ContentValues`，再通过 `ContentResolver.openOutputStream(uri)` 写入。不同目标 SDK 下的 `RELATIVE_PATH`、`IS_PENDING` 行为需要按 Android 版本处理。

简化示例：

```kotlin
fun createPublicWavUri(context: Context, displayName: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/AudioTrackPlayer"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
    }

    return context.contentResolver.insert(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        values
    )
}
```

写入完成后，在 Android 10 及以上取消 pending：

```kotlin
fun publishMediaStoreFile(context: Context, uri: Uri) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            },
            null,
            null
        )
    }
}
```

如果写入失败，应删除已经 insert 但未完成的 Uri，避免媒体库留下无效记录。

### 3. 不要把用户输入直接拼接成任意路径

错误：

```kotlin
val file = File(filesDir, userInput)
```

用户输入可能包含 `../` 或路径分隔符。至少需要限制文件名：

```kotlin
fun safeFileName(name: String): String {
    val cleaned = name
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.')

    require(cleaned.isNotEmpty()) { "Invalid file name" }
    require(cleaned != "." && cleaned != "..") { "Invalid file name" }
    return cleaned
}

val target = File(filesDir, safeFileName(userInput))
```

对更复杂的路径，还应使用 `canonicalFile` 验证最终路径仍在指定根目录下。

### 4. 不要吞掉所有异常

错误：

```kotlin
try {
    file.writeText(text)
} catch (_: Exception) {
    // 什么也不做，调用方不知道写入失败
}
```

更好的方式是返回 `Result` 或把异常交给上层：

```kotlin
fun saveText(file: File, text: String): Result<Unit> = runCatching {
    file.parentFile?.mkdirs()
    file.writeText(text, Charsets.UTF_8)
}
```

---

## 二十、针对当前录音项目的 API 选择

当前录音功能可以这样分工：

| 需求 | 推荐 API | 原因 |
|---|---|---|
| 创建录音目录 | `File(filesDir, "recordings").mkdirs()` | 应用私有、无需存储权限 |
| 接收麦克风数据 | `AudioRecord.read(ShortArray, ...)` | PCM 16-bit 采集 |
| 写 WAV PCM 数据 | `OutputStream` 或 `RandomAccessFile` | 顺序写 PCM，停止时回填头 |
| 回填 WAV 头 | `RandomAccessFile.seek()` 或 `FileChannel.position()` | 需要修改文件前部字段 |
| 解析 WAV 整数 | `ByteBuffer.order(LITTLE_ENDIAN)` | 明确按 WAV 小端格式读取 |
| 播放 PCM | `AudioTrack.write(ByteArray/ShortArray, ...)` | 将 PCM 送入音频输出 |
| 实时波形 | `ShortArray` + 峰值计算 | 不需要把每个采样点交给 UI |
| 导出到用户目录 | SAF 或 `MediaStore` | 适配分区存储和 Uri |

典型的录音写文件链路：

```kotlin
val pcmBuffer = ShortArray(44_100 / 50) // 约 20 ms，单声道

while (recording) {
    val count = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
    if (count > 0) {
        // 1. 写入 WAV data 区
        wavWriter.writePcm16(pcmBuffer, 0, count)

        // 2. 计算峰值，用于实时波形
        val peak = calculatePeak(pcmBuffer, count)
        mainHandler.post { waveformView.addAmplitude(peak) }
    }
}

// 停止后：回填 RIFF/data 长度，再允许播放
wavWriter.closeAndFinalize()
```

如果录音数据需要经过字节级封装，`ShortArray` 可以转换为 little-endian `ByteBuffer`：

```kotlin
fun shortsToPcmBytes(samples: ShortArray, count: Int): ByteArray {
    val buffer = ByteBuffer
        .allocate(count * 2)
        .order(ByteOrder.LITTLE_ENDIAN)

    for (index in 0 until count) {
        buffer.putShort(samples[index])
    }
    return buffer.array()
}
```

但如果目标只是调用 `OutputStream.write`，可以复用一个 `ByteArray` 或直接使用 `ByteBuffer` 的 backing array，避免每个采样块重复创建大量临时对象。

---

## 二十一、一个综合示例：写入并读取自定义二进制文件

假设设计一个简单文件格式：

```text
4 字节：魔数 "ATPF"
2 字节：版本号 little-endian
4 字节：记录数量 little-endian
每条记录：
  8 字节：时间戳 little-endian
  4 字节：值 little-endian
```

写入：

```kotlin
data class SampleRecord(val timestamp: Long, val value: Int)

fun writeSampleFile(file: File, records: List<SampleRecord>) {
    val recordSize = 12
    val capacity = 4 + 2 + 4 + records.size * recordSize
    val buffer = ByteBuffer
        .allocate(capacity)
        .order(ByteOrder.LITTLE_ENDIAN)

    buffer.put("ATPF".toByteArray(Charsets.US_ASCII))
    buffer.putShort(1.toShort()) // version
    buffer.putInt(records.size)

    records.forEach { record ->
        buffer.putLong(record.timestamp)
        buffer.putInt(record.value)
    }

    buffer.flip()
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { output ->
        while (buffer.hasRemaining()) {
            output.channel.write(buffer)
        }
    }
}
```

读取：

```kotlin
fun readSampleFile(file: File): List<SampleRecord> {
    FileInputStream(file).use { input ->
        val bytes = input.readBytes()
        val buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(4)
        buffer.get(magic)
        require(String(magic, Charsets.US_ASCII) == "ATPF")

        val version = buffer.short.toInt() and 0xFFFF
        require(version == 1) { "Unsupported version: $version" }

        val count = buffer.int
        require(count >= 0 && count <= buffer.remaining() / 12) {
            "Invalid record count: $count"
        }

        return List(count) {
            SampleRecord(
                timestamp = buffer.long,
                value = buffer.int
            )
        }
    }
}
```

大文件版本不应调用 `input.readBytes()`，应该先读取固定头，再按记录数量分块读取或使用 `FileChannel` 定位读取。

---

## 二十二、学习和排错清单

遇到文件读写问题时，按以下顺序排查：

1. 确认使用的是正确存储位置：`filesDir`、`cacheDir`、`Uri` 还是 `MediaStore`；
2. 打印 `absolutePath` 或 `Uri`，确认目标不是空路径；
3. 确认父目录已经创建；
4. 确认输入/输出流都在 `use` 中关闭；
5. 确认没有在主线程读写大文件；
6. 确认每次 `read/write` 都使用实际返回数量；
7. 使用 `ByteBuffer` 时打印 `position/limit/capacity/remaining`；
8. 确认 `flip/clear/compact` 的时机；
9. 确认文件格式的字节序、位宽、对齐和长度字段；
10. 确认文件头长度与实际数据长度一致；
11. 确认 `Uri` 没有被错误地转换成 `File(uri.path)`；
12. 确认错误没有被空 `catch` 吞掉。

调试 ByteBuffer 时非常有用的日志：

```kotlin
fun logBufferState(name: String, buffer: ByteBuffer) {
    Log.d(
        "ByteBuffer",
        "$name: position=${buffer.position()}, " +
            "limit=${buffer.limit()}, " +
            "capacity=${buffer.capacity()}, " +
            "remaining=${buffer.remaining()}, " +
            "direct=${buffer.isDirect}, " +
            "readOnly=${buffer.isReadOnly}, " +
            "order=${buffer.order()}"
    )
}
```

---

## 二十三、总结

文件 IO 可以按四个问题来选择 API：

1. **文件在哪儿？** 应用私有目录用 `File`，用户选择的文件用 `Uri`，公共媒体用 `MediaStore`。
2. **内容是什么？** 文本用 `Reader/Writer`，二进制用 `InputStream/OutputStream` 或 `ByteBuffer`。
3. **访问方式是什么？** 顺序访问用流，随机访问或回填头部用 `RandomAccessFile/FileChannel`。
4. **数据如何解释？** 明确字符集、字节序、位宽、对齐和长度字段。

`ByteBuffer` 最重要的不是 API 数量，而是理解它的状态转换：

```text
写入数据 → flip() → 读取数据
读取剩余数据 → compact() → 继续写入
读完准备覆盖 → clear()
重复读取当前内容 → rewind()
```

掌握这套模型后，就可以比较可靠地处理 WAV 头、PCM 数据、二进制文件、网络协议和 `FileChannel` 文件复制等场景。
