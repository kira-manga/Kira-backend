package me.manga.kira.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KiraBackendApplication

fun main(args: Array<String>) {
    runApplication<KiraBackendApplication>(*args)
}
