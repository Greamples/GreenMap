package org.greamples.greenmap.renderer

import org.greamples.greenmap.protocol.GreenMapCodec
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * Эталонный рендер сервера карты: читает пачки *.gmap и рисует регионы в PNG.
 *
 * Использование:
 *   renderer --client-jar <minecraft-client.jar> --out <dir> [--style textured|vanilla-map] <file.gmap|dir>...
 */
fun main(args: Array<String>) {
    var clientJar: Path? = null
    var out = Path.of("map-out")
    val styles = mutableListOf<RenderStyle>()
    val inputs = mutableListOf<Path>()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i++]) {
            "--client-jar" -> clientJar = Path.of(args[i++])
            "--out" -> out = Path.of(args[i++])
            "--style" -> styles += RenderStyle.valueOf(args[i++].uppercase().replace('-', '_'))
            else -> inputs.add(Path.of(arg))
        }
    }
    if (clientJar == null || inputs.isEmpty()) {
        System.err.println("usage: renderer --client-jar <minecraft-client.jar> --out <dir> [--style textured|vanilla-map] <file.gmap|dir>...")
        exitProcess(2)
    }
    if (styles.isEmpty()) styles += RenderStyle.entries

    val palette = CanonicalPalette.load(clientJar)
    println("Палитра: ${palette.blockCount} блоков из ${clientJar.name}")

    val map = WorldMap()
    val files = inputs.flatMap { if (it.isDirectory()) it.listDirectoryEntries().filter { f -> f.extension == "gmap" } else listOf(it) }
    for (file in files) Files.newInputStream(file).use { map.add(GreenMapCodec.read(it)) }
    println("Загружено ${map.size} чанков из ${files.size} файлов")

    Files.createDirectories(out)
    for (region in map.regions()) {
        val dimension = region.dimension.replace(':', '_')
        for (style in styles) {
            val file = out.resolve("${dimension}_r.${region.x}.${region.z}_${style.name.lowercase()}.png")
            ImageIO.write(RegionRenderer(palette, style).render(map, region), "png", file.toFile())
            println("→ $file")
        }
    }
}
