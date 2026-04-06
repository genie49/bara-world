# ADR-006: Agent 메타데이터는 MongoDB, 활성 여부는 Redis Heartbeat

- **상태**: 채택 (갱신)
- **일자**: 2026-04-03

## 맥락

Agent 레지스트리에서 Agent 정보와 활성 상태를 어떻게 관리할지 결정해야 했다. 초기에는 Redis에 Agent 전체 정보를 TTL과 함께 저장하는 방안이었으나, Agent 식별자를 DB 기반으로 발급하고 영구 관리할 필요가 생겼다.

## 선택지

1. **Redis 단독**: Agent 전체 정보를 Redis에 TTL과 함께 저장. SCAN으로 조회.
2. **MongoDB + Redis 분리**: MongoDB에 Agent 메타데이터 영구 저장 + Redis는 heartbeat(활성 여부)만 담당.

## 결정

**MongoDB + Redis 분리** 방식을 채택한다.

## 이유

- Agent에 시스템 발급 ID(`agent_id`)가 필요하며, 이는 영구적으로 관리되어야 함
- Agent 이름, Agent Card, skills 등 메타데이터는 heartbeat와 무관하게 유지되어야 함
- Redis는 `heartbeat:{agent_id}` 키의 존재 여부로 활성 상태만 판단 (단순, 빠름)
- 활성 Agent 조회: MongoDB에서 정보 + Redis에서 heartbeat 존재 여부 조합

## 결과

- MongoDB: Agent 영구 저장 (`agent_id`, name, provider_id, agent_card, skills)
- Redis: `heartbeat:{agent_id}` 키만 유지 (TTL 60초)
- 토픽명, ACL, 화이트리스트 등 시스템 전체에서 MongoDB 발급 `agent_id`를 식별자로 사용
