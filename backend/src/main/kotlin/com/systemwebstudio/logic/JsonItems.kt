package com.systemwebstudio.logic

import tools.jackson.databind.JsonNode

/**
 * The elements of a JSON array as a plain Kotlin list.
 *
 * Jackson 3's [JsonNode] declares members of its own (for example `map(Function)`), and a member always wins over a Kotlin extension, so
 * `node.map { ... }` does not call `Iterable.map` (and a `return` inside the lambda is prohibited). Iterate explicitly with `for`, which uses
 * `iterator()`, and do every further collection operation on the returned [List]. Call it on arrays only (an object would yield its values).
 */
internal fun JsonNode.arrayItems(): List<JsonNode> {
    val out = ArrayList<JsonNode>(size())
    for (item in this) out.add(item)
    return out
}
