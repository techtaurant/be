package com.techtaurant.mainserver.link.infrastructure.out

import java.util.UUID

interface LinkDailyStatsRepositoryCustom {
    fun aggregateStatsByLinkIds(linkIds: List<UUID>): List<LinkStatsAggregateProjection>
}
