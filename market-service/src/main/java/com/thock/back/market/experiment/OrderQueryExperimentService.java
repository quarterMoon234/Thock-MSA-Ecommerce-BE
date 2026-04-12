package com.thock.back.market.experiment;

import com.thock.back.market.app.OrderService;
import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.domain.Order;
import com.thock.back.market.in.dto.res.OrderDetailResponse;
import com.thock.back.market.out.repository.MarketMemberRepository;
import com.thock.back.market.out.repository.OrderRepository;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("experiment")
@RequiredArgsConstructor
public class OrderQueryExperimentService {

    private static final long DEFAULT_MEMBER_ID = 999_001L;
    private static final int DEFAULT_ORDER_COUNT = 100;
    private static final int DEFAULT_ITEMS_PER_ORDER = 5;
    private static final long DEFAULT_SELLER_ID = 10L;

    private final OrderRepository orderRepository;
    private final MarketMemberRepository marketMemberRepository;
    private final OrderService orderService;

    @Transactional
    public OrderQueryExperimentDatasetResponse resetDataset(Long memberId) {
        Long resolvedMemberId = resolveMemberId(memberId);
        ensureExperimentMember(resolvedMemberId);

        List<Order> orders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(resolvedMemberId);
        if (!orders.isEmpty()) {
            orderRepository.deleteAll(orders);
            orderRepository.flush();
        }

        return new OrderQueryExperimentDatasetResponse(resolvedMemberId, 0, 0);
    }

    @Transactional
    public OrderQueryExperimentDatasetResponse seedDataset(OrderQueryExperimentSeedRequest request) {
        Long memberId = resolveMemberId(request == null ? null : request.memberId());
        int orderCount = positiveOrDefault(request == null ? null : request.orderCount(), DEFAULT_ORDER_COUNT);
        int itemsPerOrder = positiveOrDefault(request == null ? null : request.itemsPerOrder(), DEFAULT_ITEMS_PER_ORDER);

        resetDataset(memberId);
        MarketMember buyer = ensureExperimentMember(memberId);

        List<Order> orders = new ArrayList<>(orderCount);
        for (int orderIndex = 0; orderIndex < orderCount; orderIndex++) {
            Order order = new Order(
                    buyer,
                    "06234",
                    "서울시 강남구 테헤란로 123",
                    "101호"
            );

            for (int itemIndex = 0; itemIndex < itemsPerOrder; itemIndex++) {
                long productSequence = ((long) orderIndex * itemsPerOrder) + itemIndex + 1L;
                long price = 10_000L + (itemIndex * 1_000L);
                long salePrice = price - 1_000L;

                order.addItem(
                        DEFAULT_SELLER_ID,
                        1_000L + productSequence,
                        "order-query-product-" + productSequence,
                        "https://example.com/images/" + productSequence + ".png",
                        price,
                        salePrice,
                        1
                );
            }

            orders.add(order);
        }

        orderRepository.saveAll(orders);
        orderRepository.flush();

        return new OrderQueryExperimentDatasetResponse(memberId, orderCount, orderCount * itemsPerOrder);
    }

    @Transactional(readOnly = true)
    public OrderQueryExperimentDatasetResponse getDataset(Long memberId) {
        Long resolvedMemberId = resolveMemberId(memberId);
        List<Order> orders = orderRepository.findDetailsByBuyerIdOrderByCreatedAtDesc(resolvedMemberId);
        int itemCount = orders.stream()
                .mapToInt(order -> order.getItems().size())
                .sum();

        return new OrderQueryExperimentDatasetResponse(resolvedMemberId, orders.size(), itemCount);
    }

    @Transactional(readOnly = true)
    public List<OrderDetailResponse> getMyOrdersBaseline(Long memberId) {
        Long resolvedMemberId = resolveMemberId(memberId);
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(resolvedMemberId).stream()
                .map(OrderDetailResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderDetailResponse> getMyOrdersOptimized(Long memberId) {
        return orderService.getMyOrders(resolveMemberId(memberId));
    }

    private MarketMember ensureExperimentMember(Long memberId) {
        return marketMemberRepository.findById(memberId)
                .orElseGet(() -> marketMemberRepository.save(new MarketMember(
                        "order-query-experiment-" + memberId + "@example.com",
                        "order-query-experiment-" + memberId,
                        MemberRole.USER,
                        MemberState.ACTIVE,
                        memberId,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )));
    }

    private Long resolveMemberId(Long memberId) {
        return memberId == null ? DEFAULT_MEMBER_ID : memberId;
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }
}
