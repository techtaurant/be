package com.techtaurant.mainserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

private const val UTC_TIME_ZONE_ID = "UTC"

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
class MainServerApplication {
    companion object {
        init {
            useUtcTimeZone()
        }
    }
}

fun main(args: Array<String>) {
    useUtcTimeZone()
    runApplication<MainServerApplication>(*args)
}

private fun useUtcTimeZone() {
    TimeZone.setDefault(TimeZone.getTimeZone(UTC_TIME_ZONE_ID))
}
