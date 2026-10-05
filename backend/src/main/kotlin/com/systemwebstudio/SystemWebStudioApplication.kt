package com.systemwebstudio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class SystemWebStudioApplication

fun main(args: Array<String>) {
    runApplication<SystemWebStudioApplication>(*args)
}