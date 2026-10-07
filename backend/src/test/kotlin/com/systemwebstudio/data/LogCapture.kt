package com.systemwebstudio.data

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures **everything** logged (root logger, TRACE) while open, including exception class/message/cause chains, so a test can assert that
 * a credential or internal address never reaches a log line.
 */
class LogCapture : AutoCloseable {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
    private val previousLevel: Level? = root.level
    init { root.level = Level.TRACE }

    val messages: List<String> get() = appender.list.toList().map { e -> e.formattedMessage + (e.throwableProxy?.let { " | " + describe(it) } ?: "") }
    val text: String get() = messages.joinToString("\n")

    private fun describe(t: IThrowableProxy?): String = if (t == null) "" else "${t.className}: ${t.message} <- ${describe(t.cause)}"

    override fun close() { root.detachAppender(appender); root.level = previousLevel }
}
