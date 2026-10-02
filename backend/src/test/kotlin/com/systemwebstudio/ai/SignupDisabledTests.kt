package com.systemwebstudio.ai

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Default configuration: no key, no sign-up. The app must still work end to end with the simulator. */
class SignupDisabledTests : IntegrationTestBase() {
    @Test
    fun `sign-up is closed by default and without a key the simulator answers`() {
        val s = session().also { it.initCsrf() }
        assertThat(s.post("/api/v1/auth/register", """{"username":"nobody1","password":"correct-horse-battery9"}""").response.status).isEqualTo(404)
        val st = sessionFor(fx.user("noai").username); val status = st.body(st.get("/api/v1/ai/status"))
        assertThat(status.get("provider").asString()).isEqualTo("mock"); assertThat(status.get("configured").asBoolean()).isFalse(); assertThat(status.get("models").size()).isEqualTo(0)
        val sc = scenario(); val b = sc.s.body(sc.prompt("bỏ phần đánh giá"))
        assertThat(b.get("provider").asString()).isEqualTo("mock"); assertThat(b.get("outcome").asString()).isEqualTo("UPDATED")
    }
}
