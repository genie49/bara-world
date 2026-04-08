# API Agent Registry 로깅 필드

## POST /agents (Agent 등록)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 생성된 Agent ID |
| provider_id | UUID | 등록한 Provider ID |
| outcome | `agent_registered` / `agent_name_already_exists` | 성공/실패 |

## GET /agents (Agent 목록)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_count | 숫자 | 반환된 Agent 수 |
| outcome | `agents_listed` | 항상 성공 |

## GET /agents/{id} (Agent 상세)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 조회 대상 Agent ID |
| outcome | `agent_retrieved` / `agent_not_found` | 성공/실패 |

## GET /agents/{id}/.well-known/agent.json (Agent Card)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 조회 대상 Agent ID |
| outcome | `agent_card_retrieved` / `agent_not_found` | 성공/실패 |

## DELETE /agents/{id} (Agent 삭제)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 삭제 대상 Agent ID |
| provider_id | UUID | 삭제 요청 Provider ID |
| outcome | `agent_deleted` / `agent_not_found` | 성공/실패 |
