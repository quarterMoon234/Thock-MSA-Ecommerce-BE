package com.thock.back.product.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.product.ProductServiceApplication;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ProductServiceApplication.class,
        properties = {
                "product.event.publish-mode=outbox",
                "product.outbox.enabled=true",
                "product.outbox.poller.enabled=false",
                "product.outbox.cleanup.enabled=false",
                "product.inbox.enabled=false"
        }
)
@ActiveProfiles("test")
class ProductStockConcurrencyIntegrationTest {

    @Autowired
    private ProductStockService productStockService;

    @Autowired
    private ProductRepository productRepository;

    @AfterEach
    void tearDown() {
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동일 상품 동시 예약 시 초과 예약이 발생하지 않는다")
    void concurrentReserve_sameProduct_preventsOversell() throws Exception {
        Product product = productRepository.save(createProduct("same-product-lock-test", 5));

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int orderIndex = i;
                futures.add(executorService.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);

                    try {
                        productStockService.handle(stockChangedEvent(
                                "ORDER-CONCURRENT-" + orderIndex,
                                List.of(new StockOrderItemDto(product.getId(), 1))
                        ));
                        return true;
                    } catch (CustomException e) {
                        return false;
                    }
                }));
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            int successCount = 0;
            int failureCount = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }

            Product reloaded = productRepository.findById(product.getId()).orElseThrow();

            assertThat(successCount).isEqualTo(5);
            assertThat(failureCount).isEqualTo(5);
            assertThat(reloaded.getReservedStock()).isEqualTo(5);
            assertThat(reloaded.getStock()).isEqualTo(5);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("역순 상품 잠금 요청도 데드락 없이 처리된다")
    void concurrentReserve_reverseOrder_completesWithoutDeadlock() throws Exception {
        Product firstProduct = productRepository.save(createProduct("lock-order-first", 10));
        Product secondProduct = productRepository.save(createProduct("lock-order-second", 10));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            Callable<Boolean> firstTask = () -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);

                productStockService.handle(stockChangedEvent(
                        "ORDER-LOCK-1",
                        List.of(
                                new StockOrderItemDto(firstProduct.getId(), 1),
                                new StockOrderItemDto(secondProduct.getId(), 1)
                        )
                ));
                return true;
            };

            Callable<Boolean> secondTask = () -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);

                productStockService.handle(stockChangedEvent(
                        "ORDER-LOCK-2",
                        List.of(
                                new StockOrderItemDto(secondProduct.getId(), 1),
                                new StockOrderItemDto(firstProduct.getId(), 1)
                        )
                ));
                return true;
            };

            Future<Boolean> firstFuture = executorService.submit(firstTask);
            Future<Boolean> secondFuture = executorService.submit(secondTask);

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            assertThat(firstFuture.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFuture.get(5, TimeUnit.SECONDS)).isTrue();

            Product reloadedFirst = productRepository.findById(firstProduct.getId()).orElseThrow();
            Product reloadedSecond = productRepository.findById(secondProduct.getId()).orElseThrow();

            assertThat(reloadedFirst.getReservedStock()).isEqualTo(2);
            assertThat(reloadedSecond.getReservedStock()).isEqualTo(2);
        } finally {
            executorService.shutdownNow();
        }
    }

    private Product createProduct(String name, int stock) {
        return Product.builder()
                .sellerId(1L)
                .category(Category.KEYBOARD)
                .name(name)
                .description("concurrency test")
                .price(10000L)
                .salePrice(9000L)
                .stock(stock)
                .imageUrl("https://example.com/product.png")
                .build();
    }

    private MarketOrderStockChangedEvent stockChangedEvent(
            String orderNumber,
            List<StockOrderItemDto> items
    ) {
        return new MarketOrderStockChangedEvent(orderNumber, StockEventType.RESERVE, items);
    }
}
