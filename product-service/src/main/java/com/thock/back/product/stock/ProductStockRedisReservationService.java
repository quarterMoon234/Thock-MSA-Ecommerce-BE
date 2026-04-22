package com.thock.back.product.stock;

import com.thock.back.shared.market.dto.StockOrderItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductStockRedisReservationService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductStockRedisProperties properties;
    private final ProductStockRedisKeyResolver keyResolver;

    private final RedisScript<Long> reserveScript = redisScript("redis/stock-reserve.lua");
    private final RedisScript<Long> releaseScript = redisScript("redis/stock-release.lua");
    private final RedisScript<Long> commitScript = redisScript("redis/stock-commit.lua");

    public ProductStockRedisReserveResult tryReserve(String orderNumber, List<StockOrderItemDto> items) {

        // 설정에서 이 기능이 꺼져있다면 즉시 종료(DISABLED)합니다.
        if (!properties.isEnabled()) {
            return ProductStockRedisReserveResult.DISABLED;
        }

        // 주문번호가 없거나 아이템이 없는 등 요청이 잘못되었다면 에러(INVALID_ARGUMENT)를 반환합니다.
        if (isInvalidRequest(orderNumber, items)) {
            return ProductStockRedisReserveResult.INVALID_ARGUMENT;
        }

        // 요청 온 상품 목록을 정리합니다. (중복된 상품 ID가 있으면 수량을 합치는 등)
        List<StockOrderItemDto> normalizedItems = normalize(items);

        // Redis Lua 스크립트에 넘길 Redis Key 배열을 만듭니다. (어떤 상품의 재고를 건드릴 것인지, 어떤 주문번호로 예약할 것인지)
        List<String> keys = reservationKeys(orderNumber, normalizedItems);

        // Redis Lua 스크립트에 넘길 인자(Arguments) 배열을 만듭니다. (몇 개를 차감할 것인지, 예약 유지 시간은 얼마인지 등)
        List<String> args = reserveArgs(normalizedItems);

        // Redis 서버에 Lua 스크립트와 데이터(keys, args)를 던져서 실행합니다. 스크립트 실행 결과(성공 여부 코드)를 받아 반환합니다.
        try {
            Long code = stringRedisTemplate.execute(reserveScript, keys, args.toArray());
            return ProductStockRedisReserveResult.fromCode(code);
        } catch (RuntimeException e) {
            log.warn(
                    "Redis stock reservation failed. orderNumber={}, itemCount={}",
                    orderNumber,
                    normalizedItems.size(),
                    e
            );
            return ProductStockRedisReserveResult.REDIS_UNAVAILABLE;
        }
    }

    // 이 메서드는 고객이 결제를 취소했거나 에러가 나서 잡아두었던 재고를 다시 풀어줄 때 사용합니다.
    public ProductStockRedisReleaseResult release(String orderNumber, List<StockOrderItemDto> items) {
        if (!properties.isEnabled()) {
            return ProductStockRedisReleaseResult.DISABLED;
        }

        if (isInvalidRequest(orderNumber, items)) {
            return ProductStockRedisReleaseResult.INVALID_ARGUMENT;
        }

        List<StockOrderItemDto> normalizedItems = normalize(items);
        List<String> keys = reservationKeys(orderNumber, normalizedItems);
        List<String> args = releaseArgs(normalizedItems);

        try {
            Long code = stringRedisTemplate.execute(releaseScript, keys, args.toArray());
            return ProductStockRedisReleaseResult.fromCode(code);
        } catch (RuntimeException e) {
            log.warn(
                    "Redis stock reservation release failed. orderNumber={}, itemCount={}",
                    orderNumber,
                    normalizedItems.size(),
                    e
            );
            return ProductStockRedisReleaseResult.REDIS_UNAVAILABLE;
        }
    }

    public ProductStockRedisCommitResult commit(String orderNumber) {
        if (!properties.isEnabled()) {
            return ProductStockRedisCommitResult.DISABLED;
        }

        if (orderNumber == null || orderNumber.isBlank()) {
            return ProductStockRedisCommitResult.INVALID_ARGUMENT;
        }

        try {
            Long code = stringRedisTemplate.execute(
                    commitScript,
                    List.of(keyResolver.reservationKey(orderNumber))
            );
            return ProductStockRedisCommitResult.fromCode(code);
        } catch (RuntimeException e) {
            log.warn("Redis stock reservation commit failed. orderNumber={}", orderNumber, e);
            return ProductStockRedisCommitResult.REDIS_UNAVAILABLE;
        }
    }

    // resources 폴더에 있는 .lua 파일을 읽어와서 RedisScript 객체로 만들어주는 유틸리티 메서드입니다. 반환 타입은 Long으로 지정되어 있습니다.
    private static RedisScript<Long> redisScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

    // 주문번호가 비어있는지, 상품 ID가 없는지, 수량이 0 이하인지 검사하는 메서드입니다.
    private boolean isInvalidRequest(String orderNumber, List<StockOrderItemDto> items) {
        if (orderNumber == null || orderNumber.isBlank() || items == null || items.isEmpty()) {
            return true;
        }

        // 아이템 중 하나라도 비정상이면 true (잘못된 요청) 반환
        return items.stream()
                .anyMatch(item -> item == null
                        || item.productId() == null
                        || item.quantity() == null
                        || item.quantity() <= 0);
    }

    /*
    1. 만약 사용자가 [상품A 1개, 상품B 2개, 상품A 2개] 처럼 동일한 상품을 나눠서 요청했다면, 이를 합쳐서 [상품A 3개, 상품B 2개]로 만듭니다. (merge 메서드 활용)
    2. TreeMap으로 키(상품 ID)를 기준으로 오름차순 정렬합니다.
     */
    private List<StockOrderItemDto> normalize(List<StockOrderItemDto> items) {
        Map<Long, Integer> quantities = new TreeMap<>();

        for (StockOrderItemDto item : items) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }

        return quantities.entrySet().stream()
                .map(entry -> new StockOrderItemDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<String> reservationKeys(String orderNumber, List<StockOrderItemDto> items) {
        List<String> keys = new ArrayList<>(items.size() + 1);

        for (StockOrderItemDto item : items) {
            keys.add(keyResolver.availableKey(item.productId())); // 재고 변경할 상품 Key
        }

        keys.add(keyResolver.reservationKey(orderNumber)); // 해당 주문 Key
        return keys;
    }

    private List<String> reserveArgs(List<StockOrderItemDto> items) {
        List<String> args = new ArrayList<>(2 + (items.size() * 2));
        args.add(String.valueOf(properties.getReservationTtlSeconds())); // 1. 예약 만료 시간(TTL)
        args.add(String.valueOf(items.size())); // 2. 처리할 상품 종류 개수

        for (StockOrderItemDto item : items) {
            args.add(String.valueOf(item.productId())); // 3. 상품 ID
            args.add(String.valueOf(item.quantity())); // 4. 차감할 수량
        }

        return args;
    }

    private List<String> releaseArgs(List<StockOrderItemDto> items) {
        List<String> args = new ArrayList<>(1 + items.size());
        args.add(String.valueOf(items.size()));

        for (StockOrderItemDto item : items) {
            args.add(String.valueOf(item.productId()));
        }

        return args;
    }
}
