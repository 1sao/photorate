package isao.photorate.inference.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal CLIP tokenizer (GPT-2-style byte-level BPE) for the MobileCLIP text
 * encoder. It loads everything it needs from a HuggingFace `tokenizer.json`:
 *
 *  - `model.vocab`  : byte-level-encoded token -> id
 *  - `model.merges` : ranked BPE merge pairs
 *  - `added_tokens` : `<|startoftext|>` (49406) and `<|endoftext|>` (49407)
 *  - pre-tokenizer regex from the JSON's Split pattern
 *
 * ktoken (the tiktoken-style library already in the project) cannot load this
 * format — it only knows OpenAI's r50k/cl100k/o200k encodings — so we implement
 * the exact CLIP algorithm here instead (same approach as the PicQuery
 * reference project). Verified against the `tokenizers` Python library:
 *   "Turtle" -> [49406, 10912, 49407], "Cat" -> [49406, 2368, 49407].
 */
class ClipTokenizer(json: String) {

    /** Byte-level encoded token -> id (from `model.vocab`). */
    private val encoder: Map<String, Int>

    /** merge pair -> rank (index in `model.merges`). */
    private val bpeRanks: Map<Pair<String, String>, Int>

    /** GPT-2 byte encoder: byte value -> char (e.g. space -> 'Ġ'). */
    private val byteEncoder: Map<Int, Char>

    private val pattern: Regex

    private val startTokenId: Int
    private val endTokenId: Int

    private val cache = mutableMapOf<String, String>()

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        val model = root.getValue("model").jsonObject
        val vocab = model.getValue("vocab").jsonObject
        encoder = vocab.mapValues { (_, v) -> v.jsonPrimitive.content.toInt() }

        val merges = model.getValue("merges").jsonArray.map { it.jsonPrimitive.content }
        bpeRanks = merges
            .mapIndexed { index, merge -> merge.substringBefore(' ') to merge.substringAfter(' ') to index }
            .toMap()

        byteEncoder = bytesToUnicode()

        // Same regex the tokenizer.json `pre_tokenizer` Split uses.
        pattern = Regex("""'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""")

        val addedTokens = root.getValue("added_tokens").jsonArray
        startTokenId = addedTokens
            .first { it.jsonObject.getValue("content").jsonPrimitive.content == "<|startoftext|>" }
            .jsonObject.getValue("id").jsonPrimitive.content.toInt()
        endTokenId = addedTokens
            .first { it.jsonObject.getValue("content").jsonPrimitive.content == "<|endoftext|>" }
            .jsonObject.getValue("id").jsonPrimitive.content.toInt()
    }

    /**
     * Tokenizes [text] into `contextLength` padded input_ids for the ONNX text
     * encoder: [<|startoftext|>, ...tokens..., <|endoftext|>, 0, 0, ...].
     * Matches CLIPProcessor (pad id 0, fixed 77-token context).
     */
    fun encode(text: String, contextLength: Int = 77): IntArray {
        // Normalizer: collapse whitespace + lowercase (same as tokenizer.json).
        val cleaned = text.trim().replace(Regex("""\s+"""), " ").lowercase()

        val ids = mutableListOf(startTokenId)
        for (match in pattern.findAll(cleaned)) {
            val token = match.value
            val bytes = token.encodeToByteArray()
            val byteLevel = bytes.joinToString("") { byteEncoder.getValue(it.toInt() and 0xFF).toString() }
            for (bpeToken in bpe(byteLevel).split(" ")) {
                ids.add(encoder.getValue(bpeToken))
            }
        }
        ids.add(endTokenId)

        if (ids.size > contextLength) {
            // Truncate like HF CLIP: keep the start token and the end token,
            // drop from the middle. (Rare for short queries but correct.)
            return IntArray(contextLength) { i ->
                when (i) {
                    0 -> ids[0]
                    contextLength - 1 -> ids.last()
                    else -> ids[i]
                }
            }
        }
        return IntArray(contextLength) { i -> ids.getOrElse(i) { 0 } }
    }

    /** Byte-level BPE merge of one pre-tokenized word (token already byte-encoded). */
    private fun bpe(token: String): String {
        cache[token]?.let { return it }

        var word = token.dropLast(1).map { it.toString() }.toMutableList().apply {
            add(token.last().toString() + "</w>")
        }
        var pairs = getPairs(word)

        if (pairs.isEmpty()) {
            return "$token</w>".also { cache[token] = it }
        }

        while (true) {
            val bigram = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (bigram !in bpeRanks) break

            val (first, second) = bigram
            val newWord = mutableListOf<String>()
            var i = 0

            while (i < word.size) {
                val j = word.subList(i, word.size).indexOf(first).takeIf { it != -1 }?.plus(i) ?: word.size
                newWord.addAll(word.subList(i, j))
                i = j

                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else if (i < word.size) {
                    newWord.add(word[i])
                    i++
                }
            }
            word = newWord
            if (word.size == 1) break
            pairs = getPairs(word)
        }

        return word.joinToString(" ").also { cache[token] = it }
    }

    private fun getPairs(word: List<String>): MutableSet<Pair<String, String>> {
        val pairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until word.size - 1) {
            pairs.add(word[i] to word[i + 1])
        }
        return pairs
    }

    /** GPT-2/CLIP byte encoder table (bytes 0-255 -> printable-ish chars). */
    private fun bytesToUnicode(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        bs.addAll(33..126)
        bs.addAll(161..172)
        bs.addAll(174..255)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        // `cs` holds Unicode code points (initial identity bytes + 256+n for
        // the gaps); convert to actual Chars so callers get byte -> char.
        return bs.zip(cs.map { it.toChar() }).toMap()
    }
}
