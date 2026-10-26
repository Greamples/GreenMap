package org.greamples.greenmap.renderer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Чем красится блок по биому — повторяет ванильные BlockColors. */
enum class Tint { NONE, GRASS, FOLIAGE, WATER, FIXED_BIRCH, FIXED_SPRUCE, FIXED_MANGROVE }

class BlockColor(val rgb: Int, val tint: Tint)

class BiomeColors(val grass: Int, val foliage: Int, val water: Int)

/**
 * Единая палитра сервера карты. Строится из ВАНИЛЬНОГО клиентского jar (assets по умолчанию),
 * а не из ресурспаков игроков: цвет блока = средний цвет верхней грани его модели,
 * плюс ванильная биомная окраска травы/листвы/воды.
 */
class CanonicalPalette private constructor(
    private val blocks: Map<String, BlockColor>,
    private val biomes: Map<String, BiomeColors>,
    private val mapColors: IntArray,
) {
    val blockCount get() = blocks.size

    /** null — блок неизвестен ванильному jar (например, модовый): рендерер возьмёт MapColor. */
    fun block(id: String): BlockColor? = blocks[id]

    fun biome(id: String): BiomeColors = biomes[id] ?: DEFAULT_BIOME

    fun mapColor(id: Int): Int = mapColors.getOrElse(id) { 0 }

    /** Итоговый цвет блока в конкретном биоме, RGB. */
    fun colorOf(blockId: String, biomeId: String, mapColorId: Int): Int? {
        val block = block(blockId) ?: return mapColor(mapColorId).takeIf { mapColorId != 0 }
        val biome = biome(biomeId)
        return when (block.tint) {
            Tint.NONE -> block.rgb
            Tint.GRASS -> multiply(block.rgb, biome.grass)
            Tint.FOLIAGE -> multiply(block.rgb, biome.foliage)
            Tint.WATER -> multiply(block.rgb, biome.water)
            Tint.FIXED_BIRCH -> multiply(block.rgb, 0x80A755)
            Tint.FIXED_SPRUCE -> multiply(block.rgb, 0x619961)
            Tint.FIXED_MANGROVE -> multiply(block.rgb, 0x92C648)
        }
    }

    companion object {
        private val DEFAULT_BIOME = BiomeColors(0x91BD59, 0x77AB2F, 0x3F76E4)
        private val TOP_TEXTURE_KEYS = listOf("top", "end", "all", "texture", "cross", "plant", "side", "particle")

        fun load(clientJar: Path): CanonicalPalette = ZipFile(clientJar.toFile()).use { zip ->
            val reader = AssetReader(zip)
            CanonicalPalette(reader.readBlocks(), reader.readBiomes(), loadMapColors())
        }

        fun multiply(a: Int, b: Int): Int {
            val r = ((a shr 16 and 0xFF) * (b shr 16 and 0xFF)) / 255
            val g = ((a shr 8 and 0xFF) * (b shr 8 and 0xFF)) / 255
            val bl = ((a and 0xFF) * (b and 0xFF)) / 255
            return (r shl 16) or (g shl 8) or bl
        }

        private fun loadMapColors(): IntArray {
            val colors = IntArray(64)
            val stream = CanonicalPalette::class.java.getResourceAsStream("/greenmap-mapcolors.txt")
                ?: error("greenmap-mapcolors.txt is missing")
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val (id, hex) = line.trim().split(' ')
                    colors[id.toInt()] = hex.toInt(16)
                }
            }
            return colors
        }

        private class AssetReader(private val zip: ZipFile) {
            private val modelCache = HashMap<String, JsonObject?>()

            fun readBlocks(): Map<String, BlockColor> {
                val result = HashMap<String, BlockColor>()
                val prefix = "assets/minecraft/blockstates/"
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith(prefix) || !entry.name.endsWith(".json")) continue
                    val name = entry.name.removePrefix(prefix).removeSuffix(".json")
                    val blockId = "minecraft:$name"
                    val model = firstModel(json(entry.name) ?: continue) ?: continue
                    val (texture, tinted) = topTexture(model) ?: continue
                    val rgb = averageColor(texture) ?: continue
                    result[blockId] = BlockColor(rgb, if (tinted) tintFor(name) else Tint.NONE)
                }
                // У воды и лавы нет обычной модели — берём их текстуры напрямую.
                averageColor("block/water_still")?.let { result["minecraft:water"] = BlockColor(it, Tint.WATER) }
                averageColor("block/lava_still")?.let { result["minecraft:lava"] = BlockColor(it, Tint.NONE) }
                return result
            }

            fun readBiomes(): Map<String, BiomeColors> {
                val grassMap = colormap("grass")
                val foliageMap = colormap("foliage")
                val result = HashMap<String, BiomeColors>()
                val prefix = "data/minecraft/worldgen/biome/"
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith(prefix) || !entry.name.endsWith(".json")) continue
                    val biome = json(entry.name) ?: continue
                    val temperature = biome.get("temperature")?.asDouble ?: 0.8
                    val downfall = biome.get("downfall")?.asDouble ?: 0.4
                    val effects = biome.getAsJsonObject("effects") ?: JsonObject()
                    var grass = effects.color("grass_color") ?: sampleColormap(grassMap, temperature, downfall, 0x91BD59)
                    val foliage = effects.color("foliage_color") ?: sampleColormap(foliageMap, temperature, downfall, 0x77AB2F)
                    val water = effects.color("water_color") ?: 0x3F76E4
                    when (effects.get("grass_color_modifier")?.asString) {
                        // Ванильные модификаторы: болото (берём основной цвет шума) и тёмный лес.
                        "swamp" -> grass = 0x6A7039
                        "dark_forest" -> grass = ((grass and 0xFEFEFE) + 0x28340A) shr 1
                    }
                    val id = "minecraft:" + entry.name.removePrefix(prefix).removeSuffix(".json")
                    result[id] = BiomeColors(grass, foliage, water)
                }
                return result
            }

            private fun JsonObject.color(key: String): Int? {
                val value = get(key) ?: return null
                return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    value.asString.removePrefix("#").toInt(16)
                } else {
                    value.asInt and 0xFFFFFF
                }
            }

            private fun colormap(name: String): IntArray? {
                val entry = zip.getEntry("assets/minecraft/textures/colormap/$name.png") ?: return null
                val image = zip.getInputStream(entry).use { ImageIO.read(it) } ?: return null
                return IntArray(256 * 256) { image.getRGB(it % 256, it / 256) and 0xFFFFFF }
            }

            /** Как GrassColor.get / FoliageColor.get в игре. */
            private fun sampleColormap(map: IntArray?, temperature: Double, downfall: Double, fallback: Int): Int {
                if (map == null) return fallback
                val t = temperature.coerceIn(0.0, 1.0)
                val d = downfall.coerceIn(0.0, 1.0) * t
                val x = ((1.0 - t) * 255.0).toInt()
                val y = ((1.0 - d) * 255.0).toInt()
                val index = (y shl 8) or x
                return if (index in map.indices) map[index] else fallback
            }

            private fun tintFor(name: String): Tint = when {
                name == "water" || name == "bubble_column" -> Tint.WATER
                name == "birch_leaves" -> Tint.FIXED_BIRCH
                name == "spruce_leaves" -> Tint.FIXED_SPRUCE
                name == "mangrove_leaves" -> Tint.FIXED_MANGROVE
                name.endsWith("leaves") || name == "vine" -> Tint.FOLIAGE
                else -> Tint.GRASS
            }

            private fun firstModel(state: JsonObject): String? {
                state.getAsJsonObject("variants")?.let { variants ->
                    val first = variants.entrySet().firstOrNull()?.value ?: return null
                    return modelName(first)
                }
                state.getAsJsonArray("multipart")?.let { parts ->
                    for (part in parts) {
                        val apply = part.asJsonObject.get("apply") ?: continue
                        return modelName(apply)
                    }
                }
                return null
            }

            private fun modelName(element: JsonElement): String? {
                val obj = if (element.isJsonArray) element.asJsonArray.firstOrNull()?.asJsonObject else element.asJsonObject
                return obj?.get("model")?.asString
            }

            /** Находит текстуру, видимую сверху: грань "up" самого высокого элемента модели. */
            private fun topTexture(modelName: String): Pair<String, Boolean>? {
                val textures = HashMap<String, String>()
                var elements: com.google.gson.JsonArray? = null
                var current: String? = modelName
                var depth = 0
                while (current != null && depth++ < 16) {
                    val model = model(current) ?: break
                    model.getAsJsonObject("textures")?.entrySet()?.forEach { (k, v) ->
                        // Текстура бывает строкой или объектом {"sprite": "...", ...}.
                        val sprite = if (v.isJsonObject) v.asJsonObject.get("sprite")?.asString else v.asString
                        if (sprite != null) textures.putIfAbsent(k, sprite)
                    }
                    if (elements == null) elements = model.getAsJsonArray("elements")
                    current = model.get("parent")?.asString
                }

                var best: Pair<String, Boolean>? = null
                var bestTop = Double.NEGATIVE_INFINITY
                elements?.forEach { element ->
                    val obj = element.asJsonObject
                    val up = obj.getAsJsonObject("faces")?.getAsJsonObject("up") ?: return@forEach
                    val top = obj.getAsJsonArray("to")?.get(1)?.asDouble ?: 0.0
                    val texture = resolve(up.get("texture")?.asString ?: return@forEach, textures) ?: return@forEach
                    if (top > bestTop) {
                        bestTop = top
                        best = texture to up.has("tintindex")
                    }
                }
                if (best != null) return best

                // Без грани сверху (цветы, трава, растения-кресты) — берём основную текстуру.
                for (key in TOP_TEXTURE_KEYS) {
                    val texture = resolve("#$key", textures) ?: continue
                    val tinted = elements?.any { el ->
                        el.asJsonObject.getAsJsonObject("faces")?.entrySet()?.any { it.value.asJsonObject.has("tintindex") } == true
                    } ?: false
                    return texture to tinted
                }
                return null
            }

            private fun resolve(reference: String, textures: Map<String, String>): String? {
                var value = reference
                var guard = 0
                while (value.startsWith("#") && guard++ < 16) value = textures[value.substring(1)] ?: return null
                return value.takeUnless { it.startsWith("#") }
            }

            private fun model(name: String): JsonObject? {
                val path = name.removePrefix("minecraft:")
                return modelCache.getOrPut(path) { json("assets/minecraft/models/$path.json") }
            }

            private fun averageColor(texture: String): Int? {
                val path = texture.removePrefix("minecraft:")
                val entry = zip.getEntry("assets/minecraft/textures/$path.png") ?: return null
                val image = zip.getInputStream(entry).use { ImageIO.read(it) } ?: return null
                // Анимированные текстуры — это кадры друг под другом: берём первый квадратный кадр.
                val size = minOf(image.width, image.height)
                var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
                for (y in 0 until size) for (x in 0 until size) {
                    val argb = image.getRGB(x, y)
                    val alpha = (argb ushr 24) / 255.0
                    if (alpha <= 0.0) continue
                    r += (argb shr 16 and 0xFF) * alpha
                    g += (argb shr 8 and 0xFF) * alpha
                    b += (argb and 0xFF) * alpha
                    weight += alpha
                }
                if (weight == 0.0) return null
                return ((r / weight).roundToInt() shl 16) or ((g / weight).roundToInt() shl 8) or (b / weight).roundToInt()
            }

            private fun json(path: String): JsonObject? {
                val entry = zip.getEntry(path) ?: return null
                return zip.getInputStream(entry).bufferedReader().use { JsonParser.parseReader(it) }
                    .takeIf { it.isJsonObject }?.asJsonObject
            }
        }
    }
}
