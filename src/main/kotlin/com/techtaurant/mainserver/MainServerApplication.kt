package com.techtaurant.mainserver

import com.techtaurant.mainserver.attachment.cli.AttachmentCli
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone
import kotlin.system.exitProcess

private const val UTC_TIME_ZONE_ID = "UTC"
private const val ATTACHMENT_CLI_COMMAND = "attachment"

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
    if (args.firstOrNull() == ATTACHMENT_CLI_COMMAND) {
        exitProcess(AttachmentCli.run(args.drop(1)))
    }
    runApplication<MainServerApplication>(*args)
}

private fun useUtcTimeZone() {
    TimeZone.setDefault(TimeZone.getTimeZone(UTC_TIME_ZONE_ID))
}
