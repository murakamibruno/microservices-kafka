#!/usr/bin/env bash
# Testes de caos do Outbox (SDD seção 9). Requer a stack no ar: docker compose up -d --build
# Uso: scripts/chaos-test.sh [N pedidos por cenário, padrão 10]
set -uo pipefail

N=${1:-10}
ORDER_URL=${ORDER_URL:-http://localhost:3000}
TIMEOUT=${TIMEOUT:-120}
FAILURES=0

body='{"products":[{"product":{"code":"COMIC_BOOKS","unitValue":15.5},"quantity":1}]}'

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pass() { printf '  \033[32mOK\033[0m   %s\n' "$*"; }
fail() { printf '  \033[31mFALHA\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

json() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1') or '')"; }

mongo() {
  docker exec order-db mongosh --quiet -u admin -p 123456 --authenticationDatabase admin admin --eval "$1"
}

metric() { # metric <porta> <nome prometheus>
  curl -s "http://localhost:$1/actuator/prometheus" | awk -v m="$2" '$1==m {print $2}' | head -1
}

wait_http() { # espera o order-service responder
  local deadline=$((SECONDS + TIMEOUT))
  until curl -sf "$ORDER_URL/actuator/health" >/dev/null 2>&1; do
    [ $SECONDS -ge $deadline ] && return 1
    sleep 2
  done
}

ORDER_IDS=()
create_orders() { # create_orders <n> -> preenche ORDER_IDS
  ORDER_IDS=()
  local i code res
  for i in $(seq 1 "$1"); do
    res=$(curl -s -m 20 -w '\n%{http_code}' -X POST "$ORDER_URL/api/order" -H 'Content-Type: application/json' -d "$body")
    code=${res##*$'\n'}
    if [ "$code" != "200" ]; then fail "POST /api/order retornou HTTP $code"; continue; fi
    ORDER_IDS+=("$(echo "${res%$'\n'*}" | json id)")
  done
}

saga_status() { curl -s "$ORDER_URL/api/event?orderId=$1" | json status; }

wait_sagas() { # espera todas as sagas de ORDER_IDS terminarem; imprime quantas SUCCESS
  local deadline=$((SECONDS + TIMEOUT)) pending ok id st
  while :; do
    pending=0; ok=0
    for id in "${ORDER_IDS[@]}"; do
      st=$(saga_status "$id")
      if [ "$st" = "SUCCESS" ]; then ok=$((ok + 1)); elif [ "$st" != "FAIL" ]; then pending=$((pending + 1)); fi
    done
    [ $pending -eq 0 ] && break
    [ $SECONDS -ge $deadline ] && break
    sleep 3
  done
  echo "$ok"
}

assert_sagas_complete() {
  local ok; ok=$(wait_sagas)
  if [ "$ok" = "${#ORDER_IDS[@]}" ]; then pass "$ok/${#ORDER_IDS[@]} sagas concluídas com SUCCESS"
  else fail "apenas $ok/${#ORDER_IDS[@]} sagas concluídas com SUCCESS"; fi
}

assert_no_duplicates() {
  local dup; dup=$(docker exec payment-db psql -U postgres -d payment-db -tAc \
    "select count(*) - count(distinct transaction_id) from payment" | tr -d '[:space:]')
  if [ "$dup" = "0" ]; then pass "nenhum pagamento duplicado"; else fail "$dup pagamentos duplicados"; fi
}

assert_outbox_drained() {
  sleep 3
  local pending; pending=$(mongo "print(db.outbox.countDocuments({status:{\$ne:'SENT'}}))" | tr -d '[:space:]')
  if [ "$pending" = "0" ]; then pass "outbox do order-service sem pendências"; else fail "$pending mensagens não-SENT no outbox do order-service"; fi
}

# Estoque do seed é pequeno (COMIC_BOOKS = 4): repõe para que as sagas terminem em SUCCESS
docker exec inventory-db psql -U postgres -d inventory-db -qc "update inventory set available = 100000" >/dev/null

# --- Cenário 1 (CA-01): Kafka parado --------------------------------------------------
log "Cenário 1: Kafka parado, $N pedidos, Kafka religado"
wait_http || { fail "order-service não respondeu"; exit 1; }
docker compose stop kafka >/dev/null 2>&1
create_orders "$N"
[ "${#ORDER_IDS[@]}" -eq "$N" ] && pass "POST /api/order retornou 200 para os $N pedidos com o Kafka parado"
sleep 8
pending=$(mongo "print(db.outbox.countDocuments({status:{\$in:['PENDING','PROCESSING']}}))" | tr -d '[:space:]')
[ "$pending" -ge "$N" ] && pass "$pending mensagens retidas no outbox (PENDING/PROCESSING)" || fail "esperava >= $N mensagens retidas, achei $pending"
pending_metric=$(metric 3000 outbox_pending_count)
[ -n "$pending_metric" ] && pass "métrica outbox_pending_count = $pending_metric" || fail "métrica outbox_pending_count ausente"
docker compose start kafka >/dev/null 2>&1
assert_sagas_complete
assert_no_duplicates
assert_outbox_drained

# --- Cenário 2: serviço morto entre o commit e a publicação ----------------------------
kill_scenario() { # kill_scenario <container>
  log "Cenário: kill -9 em $1 logo após criar $N pedidos"
  create_orders "$N"
  docker kill "$1" >/dev/null 2>&1
  sleep 5
  docker start "$1" >/dev/null 2>&1
  wait_http || { fail "order-service não respondeu"; return; }
  assert_sagas_complete
  assert_no_duplicates
  assert_outbox_drained
}
kill_scenario order-service
kill_scenario payment-service
kill_scenario inventory-service

# --- Resultado -------------------------------------------------------------------------
log "Resultado"
if [ $FAILURES -eq 0 ]; then printf '\033[32mTodos os cenários passaram\033[0m\n'; else printf '\033[31m%d verificações falharam\033[0m\n' "$FAILURES"; exit 1; fi
