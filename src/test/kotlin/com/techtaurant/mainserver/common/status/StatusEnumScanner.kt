package com.techtaurant.mainserver.common.status

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

private const val BASE_PACKAGE = "com.techtaurant.mainserver"
private const val MAIN_CLASSES_PATH = "/classes/kotlin/main/"

/**
 * 운영 코드에 선언된 StatusIfs 구현 enum을 모두 찾는다.
 * 새 도메인 enum이 추가돼도 상태 코드 검사 테스트가 자동으로 포함하도록 클래스패스를 스캔한다.
 */
internal fun scanStatusEnums(): List<Class<out StatusIfs>> {
    val scanner =
        object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean = true
        }
    scanner.addIncludeFilter(AssignableTypeFilter(StatusIfs::class.java))

    return scanner
        .findCandidateComponents(BASE_PACKAGE)
        .mapNotNull { it.beanClassName }
        .map { Class.forName(it) }
        .filter { it.isEnum && isProductionClass(it) }
        .map {
            @Suppress("UNCHECKED_CAST")
            it as Class<out StatusIfs>
        }
}

private fun isProductionClass(candidate: Class<*>): Boolean {
    val classLocation = candidate.protectionDomain?.codeSource?.location?.path ?: return false
    return classLocation.contains(MAIN_CLASSES_PATH)
}
