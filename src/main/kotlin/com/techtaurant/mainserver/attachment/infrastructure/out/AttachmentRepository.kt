package com.techtaurant.mainserver.attachment.infrastructure.out

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

interface AttachmentRepository : Repository<Attachment, UUID>, AttachmentRepositoryCustom {
    override fun save(attachment: Attachment): Attachment

    override fun saveAll(attachments: Iterable<Attachment>): List<Attachment>

    override fun deleteAll(attachments: Iterable<Attachment>)

    override fun deleteAllInBatch()

    override fun existsById(id: UUID): Boolean

    override fun findAllById(ids: Iterable<UUID>): List<Attachment>

    /**
     * 첨부 확정 트랜잭션 동안 요청된 첨부 행을 ID 순서로 잠급니다.
     *
     * @param ids 잠글 첨부 ID 목록
     * @return 존재하는 첨부 목록
     */
    override fun findAllByIdForUpdate(ids: Iterable<UUID>): List<Attachment>

    /**
     * 요청된 첨부의 소유 대상만 한 번의 UPDATE로 기록합니다.
     *
     * @param referenceId 기록할 연관 도메인 PK
     * @param attachmentIds 갱신할 첨부 ID 목록
     */
    override fun updateReferenceIdByIds(
        referenceId: UUID,
        attachmentIds: List<UUID>,
    )

    /**
     * 보관 기간이 지나고 아직 어느 대상에도 소유가 기록되지 않은 특정 상태의 첨부를
     * 한 번에 처리할 수 있는 만큼만 조회합니다.
     *
     * 소유가 기록된 첨부는 게시물 삭제와 orphan 정리가 referenceId로 찾을 수 있으므로 제외한다.
     * 임시저장은 확정 없이 소유만 기록해 status를 TMP로 남기기 때문에, status만 보면
     * 편집 중인 임시저장의 첨부까지 만료 대상이 된다.
     *
     * @param status 조회할 첨부 상태
     * @param createdAtBefore 이 시각 이전에 생성된 첨부만 반환
     * @param limit 최대 반환 건수
     * @return 조건에 맞는 첨부 목록
     */
    override fun findAllUnclaimedByStatusAndCreatedAtBefore(
        status: AttachmentStatus,
        createdAtBefore: Instant,
        limit: Int,
    ): List<Attachment>

    override fun findAllByObjectKeyInAndStatus(
        objectKeys: List<String>,
        status: AttachmentStatus,
    ): List<Attachment>

    override fun findAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): List<Attachment>

    override fun findAllByReferenceIdAndReferenceTypeAndIdNotIn(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ): List<Attachment>

    override fun findAllByReferenceIdInAndReferenceType(
        referenceIds: List<UUID>,
        referenceType: AttachmentReferenceType,
    ): List<Attachment>

    override fun deleteAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    )
}
