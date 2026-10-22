package io.github.localplaylist

import java.nio.file.Path

/** 只读预览用户提供的 TXT，不访问或修改歌单。 */
object PreviewLoved {
@JvmStatic fun main(args: Array<String>) {
    val rows = TxtReader.read(Path.of(args.single()))
    println("Total=${rows.size}")
    rows.groupingBy { it.state }.eachCount().forEach { (state, count) -> println("${state.name}=$count") }
    rows.filter { it.state == RowState.MISSING }.forEach { println("Missing line ${it.line}: ${it.path}") }
}
}
