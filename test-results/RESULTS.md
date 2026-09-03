# API 測試結果 (2026-08-28)

所有端點皆使用開發帳號 `dev-buyer` / `dev-buyer-password` 進行測試。

## 1. GET /api/v1/dashboard/summary
```json
$(cat test-results/summary.json)
```

## 2. GET /api/v1/dashboard/rankings
```json
$(cat test-results/rankings.json)
```

## 3. GET /api/v1/dashboard/sourcing-summary (limit=3)
```json
$(cat test-results/sourcing-summary.json)
```

## 4. GET /api/v1/dashboard/todos
```json
$(cat test-results/todos.json)
```

## 5. GET /api/v1/dashboard/heat-sources
```json
$(cat test-results/heat-sources.json)
```

所有回應均為 `200 OK`，且 `success: true`，資料結構符合 OpenAPI 定義。
