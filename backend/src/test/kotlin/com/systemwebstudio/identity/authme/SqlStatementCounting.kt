package com.systemwebstudio.identity.authme

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.util.ClassUtils
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * Test-only SQL statement counter (pure JDK, no extra dependency). Every execute / executeQuery / executeUpdate / executeBatch / executeLargeUpdate /
 * executeLargeBatch call on a Statement / PreparedStatement / CallableStatement obtained from a wrapped DataSource is counted, globally and per calling thread.
 * MockMvc runs the request on the test thread, so [countOnCurrentThread] isolates the request from background schedulers that share the pool.
 */
class SqlStatementCounter {
    private val total = AtomicLong()
    private val perThread = ConcurrentHashMap<Long, AtomicLong>()

    fun record() {
        total.incrementAndGet()
        perThread.computeIfAbsent(Thread.currentThread().threadId()) { AtomicLong() }.incrementAndGet()
    }
    fun reset() { total.set(0); perThread.clear() }
    /** every counted statement since [reset], on any thread */
    fun count(): Long = total.get()
    /** statements executed by the calling thread since [reset] */
    fun countOnCurrentThread(): Long = perThread[Thread.currentThread().threadId()]?.get() ?: 0L
}

/** Behaviour-preserving JDK proxies: everything delegates; only the execute* calls are counted and the returned connections / statements are wrapped. */
internal object CountingJdbcProxies {
    private val EXECUTE = setOf("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeUpdate", "executeLargeBatch")

    private fun interfacesOf(target: Any): Array<Class<*>> = ClassUtils.getAllInterfacesForClass(target.javaClass, target.javaClass.classLoader)

    private fun <T> proxy(target: Any, required: Class<T>, handler: InvocationHandler): Any {
        val ifaces = (interfacesOf(target).toList() + required).distinct().toTypedArray()
        // the target's loader sees both java.sql and the driver / pool interfaces (java.sql's own loader is 'platform' and does not see e.g. HikariConfigMXBean)
        val loader = target.javaClass.classLoader ?: Thread.currentThread().contextClassLoader
        return Proxy.newProxyInstance(loader, ifaces, handler)
    }

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    /** identity semantics for the proxy itself (Spring compares connections with ==/equals); everything else goes to the target */
    private fun objectMethod(proxy: Any, target: Any, method: Method, args: Array<out Any?>?): Pair<Boolean, Any?> = when (method.name) {
        "equals" -> if (method.parameterCount == 1) true to (proxy === args!![0]) else false to null
        "hashCode" -> if (method.parameterCount == 0) true to System.identityHashCode(proxy) else false to null
        "toString" -> if (method.parameterCount == 0) true to "Counting[$target]" else false to null
        else -> false to null
    }

    fun dataSource(target: DataSource, counter: () -> SqlStatementCounter): Any = proxy(target, DataSource::class.java) { p, m, a ->
        val (handled, r) = objectMethod(p, target, m, a)
        if (handled) return@proxy r
        val result = invoke(target, m, a)
        if (m.name == "getConnection" && result is Connection) connection(result, counter) else result
    }

    fun connection(target: Connection, counter: () -> SqlStatementCounter): Connection {
        lateinit var self: Connection
        self = proxy(target, Connection::class.java) { p, m, a ->
            val (handled, r) = objectMethod(p, target, m, a)
            if (handled) return@proxy r
            val result = invoke(target, m, a)
            if (result is Statement && (m.name == "createStatement" || m.name == "prepareStatement" || m.name == "prepareCall")) statement(result, self, counter) else result
        } as Connection
        return self
    }

    fun statement(target: Statement, owner: Connection, counter: () -> SqlStatementCounter): Statement =
        proxy(target, Statement::class.java) { p, m, a ->
            val (handled, r) = objectMethod(p, target, m, a)
            if (handled) return@proxy r
            if (m.name in EXECUTE) counter().record()
            if (m.name == "getConnection" && m.parameterCount == 0) owner else invoke(target, m, a)
        } as Statement
}

/** Import together with IntegrationTestBase: every DataSource bean of the context is wrapped; the counter is a bean. */
@TestConfiguration(proxyBeanMethods = false)
class SqlStatementCountingConfiguration {
    companion object {
        @JvmStatic @Bean
        fun sqlStatementCounter(): SqlStatementCounter = SqlStatementCounter()

        @JvmStatic @Bean
        fun countingDataSourcePostProcessor(counter: ObjectProvider<SqlStatementCounter>): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is DataSource && !Proxy.isProxyClass(bean.javaClass)) CountingJdbcProxies.dataSource(bean) { counter.getObject() } else bean
        }
    }
}
