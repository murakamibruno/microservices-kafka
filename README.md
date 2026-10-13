**POST** http://localhost:3000/api/order

Payload:

```json
{
  "products": [
    {
      "product": {
        "code": "COMIC_BOOKS",
        "unitValue": 15.50
      },
      "quantity": 3
    },
    {
      "product": {
        "code": "BOOKS",
        "unitValue": 9.90
      },
      "quantity": 1
    }
  ]
}
```

Resposta:

```json
{
  "id": "64429e987a8b646915b3735f",
  "products": [
    {
      "product": {
        "code": "COMIC_BOOKS",
        "unitValue": 15.5
      },
      "quantity": 3
    },
    {
      "product": {
        "code": "BOOKS",
        "unitValue": 9.9
      },
      "quantity": 1
    }
  ],
  "createdAt": "2023-04-21T14:32:56.335943085",
  "transactionId": "1682087576536_99d2ca6c-f074-41a6-92e0-21700148b519"
}
```

### Endpoint para visualizar a saga:

É possível recuperar os dados da saga pelo **orderId** ou pelo **transactionId**, o resultado será o mesmo:

**GET** http://localhost:3000/api/event?orderId=64429e987a8b646915b3735f

**GET** http://localhost:3000/api/event?transactionId=1682087576536_99d2ca6c-f074-41a6-92e0-21700148b519

Resposta:

```json
{
  "id": "64429e9a7a8b646915b37360",
  "transactionId": "1682087576536_99d2ca6c-f074-41a6-92e0-21700148b519",
  "orderId": "64429e987a8b646915b3735f",
  "payload": {
    "id": "64429e987a8b646915b3735f",
    "products": [
      {
        "product": {
          "code": "COMIC_BOOKS",
          "unitValue": 15.5
        },
        "quantity": 3
      },
      {
        "product": {
          "code": "BOOKS",
          "unitValue": 9.9
        },
        "quantity": 1
      }
    ],
    "totalAmount": 56.40,
    "totalItems": 4,
    "createdAt": "2023-04-21T14:32:56.335943085",
    "transactionId": "1682087576536_99d2ca6c-f074-41a6-92e0-21700148b519"
  },
  "source": "ORCHESTRATOR",
  "status": "SUCCESS",
  "eventHistory": [
    {
      "source": "ORCHESTRATOR",
      "status": "SUCCESS",
      "message": "Saga started!",
      "createdAt": "2023-04-21T14:32:56.78770516"
    },
    {
      "source": "PRODUCT_VALIDATION_SERVICE",
      "status": "SUCCESS",
      "message": "Products are validated successfully!",
      "createdAt": "2023-04-21T14:32:57.169378616"
    },
    {
      "source": "PAYMENT_SERVICE",
      "status": "SUCCESS",
      "message": "Payment realized successfully!",
      "createdAt": "2023-04-21T14:32:57.617624655"
    },
    {
      "source": "INVENTORY_SERVICE",
      "status": "SUCCESS",
      "message": "Inventory updated successfully!",
      "createdAt": "2023-04-21T14:32:58.139176809"
    },
    {
      "source": "ORCHESTRATOR",
      "status": "SUCCESS",
      "message": "Saga finished successfully!",
      "createdAt": "2023-04-21T14:32:58.248630293"
    }
  ],
  "createdAt": "2023-04-21T14:32:58.28"
}
```

## Outbox / Inbox e resiliência

Os serviços `order`, `product-validation`, `payment` e `inventory` gravam o evento de saída numa tabela/coleção `outbox` **na mesma transação** dos dados de negócio; um relay (`OutboxRelay`) publica no Kafka com `key = transactionId` e os headers `x-message-id`, `x-transaction-id` e `x-source`. Os consumidores deduplicam pelo `inbox` `(topic, transactionId)`. O `orchestrator-service` não tem banco: publica de forma síncrona, com `x-message-id` determinístico, e mensagens que falham repetidamente vão para `<topic>.DLT`. O desenho completo está em [`docs/sdd/outbox-pattern.md`](docs/sdd/outbox-pattern.md).

O MongoDB do `order-service` roda como **replica set de um nó** (necessário para transações). Para rodar o `order-service` pela IDE, a URI padrão já usa `directConnection=true`.

### Observabilidade

Os quatro serviços acima expõem `/actuator/health`, `/actuator/metrics` e `/actuator/prometheus` na própria porta (3000, 8090, 8091 e 8092):

| Métrica | O que indica |
|---------|--------------|
| `outbox_pending_count` | mensagens aguardando publicação |
| `outbox_oldest_pending_age_seconds` | idade da mais antiga pendente (principal alerta de saga travada) |
| `outbox_failed_count` | mensagens que esgotaram as tentativas (`FAILED`) e precisam de intervenção |
| `outbox_published_total` / `outbox_publish_failures_total` | publicações com sucesso / falhas |
| `inbox_duplicates_total` | mensagens duplicadas descartadas pelo inbox |

Mensagens `SENT` são expurgadas pelo `OutboxCleanupJob` após `OUTBOX_RETENTION_HOURS` (padrão 72 h; cron em `OUTBOX_CLEANUP_CRON`). Demais variáveis: `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_SEND_TIMEOUT_MS`, `OUTBOX_MAX_ATTEMPTS`.

### Testes de caos

Com a stack no ar (`docker compose up -d --build`):

```bash
scripts/chaos-test.sh 10   # 10 pedidos por cenário
```

O script derruba o Kafka e depois dá `kill -9` em `order-service`, `payment-service` e `inventory-service` logo após criar os pedidos; em seguida verifica que todas as sagas concluem, que não há pagamentos duplicados e que o outbox esvazia. Ele repõe o estoque do seed (`update inventory`) antes de começar.

