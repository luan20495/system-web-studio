package com.systemwebstudio.component

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ComponentApiTests : IntegrationTestBase() {
    @Test
    fun `details=true returns every component with its props schema in one call, plain list has none`() {
        val s = sessionFor(fx.user("reg").username)
        val plain = s.body(s.get("/api/v1/components"))
        assertThat(plain.size()).isEqualTo(10); assertThat(plain.get(0).get("versions").size()).isEqualTo(0)
        val detailed = s.body(s.get("/api/v1/components?details=true"))
        assertThat(detailed.size()).isEqualTo(10)
        val hero = detailed.toList().first { it.get("id").asString() == "Hero" }
        assertThat(hero.get("versions").get(0).get("propsSchema").get("properties").has("title")).isTrue()
        assertThat(session().get("/api/v1/components?details=true").response.status).isEqualTo(401)
    }
}
