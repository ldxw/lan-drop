package top.ldxw.landrop

import java.io.File

/** 接收目录的遍历与相对路径换算（纯 java.io，方便在 JVM 上测）。 */
object Storage {

    fun walk(dir: File): List<File> {
        val out = ArrayList<File>()
        collect(dir, out)
        return out
    }

    private fun collect(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) collect(c, out) else out.add(c)
        }
    }

    fun relName(root: File, f: File): String =
        f.canonicalPath.removePrefix(root.canonicalPath + File.separator).replace(File.separatorChar, '/')
}
