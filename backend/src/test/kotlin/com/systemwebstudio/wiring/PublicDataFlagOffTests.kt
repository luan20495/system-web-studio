package com.systemwebstudio.wiring

import com.systemwebstudio.access.adapters.DenyAllPublicQueryAllowList
import com.systemwebstudio.access.adapters.PublicQueryAllowList
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/** D-C0-36 · with `app.sites.public-data.enabled` off (the default) nothing of the Public Runtime exists: no controller, no filter, no provider (C1 keeps DENY ALL), and the route answers 404. */
class PublicDataFlagOffTests : IntegrationTestBase() {
    @Autowired lateinit var ctx: ApplicationContext

    @Test
    fun `no controller, no provider bean and no answer on the route`() {
        assertThat(ctx.getBeansOfType(PublicDataController::class.java)).isEmpty()
        assertThat(ctx.getBeansOfType(PublicDataBodyLimitFilter::class.java)).isEmpty()
        assertThat(ctx.getBeansOfType(PublicQueryAllowList::class.java)).isEmpty()
        assertThat(DenyAllPublicQueryAllowList.publicQueryIds(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID())).isEmpty()
        val r = mvc.perform(post("/sites/anything-123/_data/queries/orders-list/run").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn()
        assertThat(r.response.status).describedAs("the site catch-all is GET only; nothing answers a data call").isIn(404, 405)
    }
}
