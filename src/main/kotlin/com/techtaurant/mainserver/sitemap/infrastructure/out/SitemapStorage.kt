package com.techtaurant.mainserver.sitemap.infrastructure.out

/** 생성된 사이트맵 XML을 지정한 경로에 게시하는 저장 계약입니다. */
interface SitemapStorage {
    fun publishXml(
        key: String,
        xml: ByteArray,
    )
}
