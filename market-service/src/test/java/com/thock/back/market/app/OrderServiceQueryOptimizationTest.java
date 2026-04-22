package com.thock.back.market.app;

import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.domain.Order;
import com.thock.back.market.domain.OrderState;
import com.thock.back.market.in.dto.res.OrderDetailResponse;
import com.thock.back.market.out.repository.MarketMemberRepository;
import com.thock.back.market.out.repository.OrderRepository;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderServiceQueryOptimizationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MarketMemberRepository marketMemberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private Long buyerId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.clear();

        MarketMember buyer = marketMemberRepository.save(new MarketMember(
                "buyer@test.com",
                "buyer",
                MemberRole.USER,
                MemberState.ACTIVE,
                1L,
                LocalDateTime.now(),
                LocalDateTime.now()
        ));

        for (int i = 0; i < 5; i++) {
            Order order = new Order(buyer, "06234", "서울시 강남구", "101호");
            order.addItem(10L, 100L + i, "상품-" + i + "-1", "https://image/" + i + "-1", 10000L, 9000L, 1);
            order.addItem(10L, 200L + i, "상품-" + i + "-2", "https://image/" + i + "-2", 15000L, 12000L, 2);
            orderRepository.save(order);
        }

        orderRepository.flush();
        entityManager.clear();

        buyerId = buyer.getId();
        orderId = orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).get(0).getId();

        statistics.clear();
        entityManager.clear();
    }

    @Test
    void getMyOrders_reducesQueriesComparedToLazyLoadingBaseline() {
        long baselineQueryCount = baselineMyOrdersQueryCount();

        statistics.clear();
        entityManager.clear();

        List<OrderDetailResponse> responses = orderService.getMyOrders(buyerId);
        long optimizedQueryCount = statistics.getPrepareStatementCount();

        assertThat(responses).hasSize(5);
        assertThat(baselineQueryCount).isEqualTo(6);
        assertThat(optimizedQueryCount).isEqualTo(1);
    }

    @Test
    void getOrderDetail_fetchesItemsInSingleQuery() {
        long baselineQueryCount = baselineOrderDetailQueryCount();

        statistics.clear();
        entityManager.clear();

        OrderDetailResponse response = orderService.getOrderDetail(buyerId, orderId);
        long optimizedQueryCount = statistics.getPrepareStatementCount();

        assertThat(response.items()).hasSize(2);
        assertThat(baselineQueryCount).isEqualTo(2);
        assertThat(optimizedQueryCount).isEqualTo(1);
    }

    private long baselineMyOrdersQueryCount() {
        List<Order> orders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId);
        List<OrderDetailResponse> ignored = orders.stream()
                .map(OrderDetailResponse::from)
                .toList();

        assertThat(ignored).hasSize(5);
        return statistics.getPrepareStatementCount();
    }

    private long baselineOrderDetailQueryCount() {
        Order order = orderRepository.findById(orderId).orElseThrow();
        OrderDetailResponse ignored = OrderDetailResponse.from(order);

        assertThat(ignored.items()).hasSize(2);
        return statistics.getPrepareStatementCount();
    }
}
