package com.systemwebstudio.data.query

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeType
import tools.jackson.databind.node.ObjectNode

/** The one place the Data Platform touches the Jackson API, so a Jackson upgrade has a single blast radius. */
internal object DataJson {
    val mapper: JsonMapper = JsonMapper.builder().build()

    fun parse(bytes: ByteArray): JsonNode = mapper.readTree(bytes)
    /** JSON null (`valueToTree(null)` is not the same thing in every Jackson version) */
    val NULL: JsonNode by lazy { parse("null".toByteArray()) }
    fun obj(): ObjectNode = mapper.createObjectNode()
    fun toNode(value: Any?): JsonNode = mapper.valueToTree<JsonNode>(value)
    /** plain Java value of a JSON node: String, Integer/Long/Double/BigInteger, Boolean, null, List, Map */
    fun toJava(node: JsonNode): Any? = mapper.treeToValue(node, Any::class.java)
    fun text(node: JsonNode): String = node.asString()
    fun keys(node: JsonNode): List<String> = node.propertyNames().asSequence().toList()
    /**
     * The elements of an array node as a Kotlin list, by explicit iteration. Jackson 3's `JsonNode` has members of its own (such as `map`) that are chosen
     * instead of Kotlin's `Iterable` extensions, so C3 never calls `map`/`all`/`take`/`toList`... on a node directly: it goes through this list.
     */
    fun elements(node: JsonNode): List<JsonNode> { val out = ArrayList<JsonNode>(node.size()); for (e in node) out.add(e); return out }
    /** object or array node. `isContainerNode` is gone in Jackson 3; `isObject`/`isArray` exist in both, so this is spelled with them rather than guessed. */
    fun isContainer(node: JsonNode): Boolean = node.isObject || node.isArray
    fun type(node: JsonNode?): JsonNodeType? = node?.nodeType
    /** text node (Jackson 2 `isTextual`, Jackson 3 `isString`: asked through the node type so both compile) */
    fun isText(node: JsonNode?): Boolean = node?.nodeType == JsonNodeType.STRING
}
