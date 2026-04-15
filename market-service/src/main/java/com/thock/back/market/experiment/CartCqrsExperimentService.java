package com.thock.back.market.experiment;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.app.CartService;
import com.thock.back.market.app.MarketSupport;
import com.thock.back.market.domain.Cart;
import com.thock.back.market.domain.CartItem;
import com.thock.back.market.domain.CartProductView;
import com.thock.back.market.domain.CartProductViewRepository;
import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.in.dto.req.CartItemAddRequest;
import com.thock.back.market.in.dto.res.CartItemListResponse;
import com.thock.back.market.in.dto.res.CartItemResponse;
import com.thock.back.market.out.api.dto.ProductInfo;
import com.thock.back.market.out.client.ProductCartCqrsExperimentClient;
import com.thock.back.market.out.client.ProductClient;
import com.thock.back.market.out.repository.CartRepository;
import com.thock.back.market.out.repository.MarketMemberRepository;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("experiment")
@RequiredArgsConstructor
public class CartCqrsExperimentService {

    private static final long DEFAULT_BASE_MEMBER_ID = 980_000L;
    private static final int DEFAULT_READ_MEMBER_COUNT = 50;
    private static final int DEFAULT_ADD_MEMBER_COUNT_PER_SCENARIO = 100;
    private static final int DEFAULT_READ_ITEM_QUANTITY = 1;
    private static final int DEFAULT_ADD_ITEM_QUANTITY = 1;

    private final MarketMemberRepository marketMemberRepository;
    private final CartRepository cartRepository;
    private final CartProductViewRepository cartProductViewRepository;
    private final ProductClient productClient;
    private final ProductCartCqrsExperimentClient productCartCqrsExperimentClient;
    private final MarketSupport marketSupport;
    private final CartService cartService;

    @Transactional
    public CartCqrsExperimentDatasetResponse seedDataset(CartCqrsExperimentSeedRequest request) {
        if (request == null || request.productIds() == null || request.productIds().isEmpty()) {
            throw new CustomException(ErrorCode.CART_PRODUCT_INFO_NOT_FOUND);
        }

        long baseMemberId = request.baseMemberId() == null ? DEFAULT_BASE_MEMBER_ID : request.baseMemberId();
        int readMemberCount = positiveOrDefault(request.readMemberCount(), DEFAULT_READ_MEMBER_COUNT);
        int addMemberCountPerScenario = positiveOrDefault(
                request.addMemberCountPerScenario(),
                DEFAULT_ADD_MEMBER_COUNT_PER_SCENARIO
        );
        int readItemQuantity = positiveOrDefault(request.readItemQuantity(), DEFAULT_READ_ITEM_QUANTITY);

        List<Long> productIds = request.productIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (productIds.isEmpty()) {
            throw new CustomException(ErrorCode.CART_PRODUCT_INFO_NOT_FOUND);
        }

        CartCqrsExperimentDatasetResponse dataset = buildDatasetPlan(baseMemberId, productIds, readMemberCount, addMemberCountPerScenario);

        upsertCartProductViews(productIds);
        prepareMembersAndCarts(dataset);
        seedReadCarts(dataset.readMemberIds(), productIds, readItemQuantity);

        return dataset;
    }

    @Transactional(readOnly = true)
    public CartItemListResponse getBaselineCartItems(Long memberId, long productDelayMs) {
        MarketMember member = marketSupport.findMemberById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_USER_NOT_FOUND));
        Cart cart = marketSupport.findCartByBuyer(member)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_NOT_FOUND));

        if (!cart.hasItems()) {
            return new CartItemListResponse(cart.getId(), List.of(), 0, 0L, 0L, 0L);
        }

        List<Long> productIds = cart.getItems().stream()
                .map(CartItem::getProductId)
                .toList();

        Map<Long, ProductInfo> productMap = fetchProducts(productIds, productDelayMs).stream()
                .collect(Collectors.toMap(ProductInfo::getId, product -> product, (left, right) -> left, LinkedHashMap::new));

        List<CartItemResponse> items = cart.getItems().stream()
                .map(cartItem -> {
                    ProductInfo product = productMap.get(cartItem.getProductId());
                    if (product == null) {
                        return null;
                    }
                    return CartItemResponse.from(cartItem, product);
                })
                .filter(Objects::nonNull)
                .toList();

        return CartItemListResponse.from(cart, items);
    }

    @Transactional
    public CartItemResponse addBaselineCartItem(Long memberId, CartItemAddRequest request, long productDelayMs) {
        MarketMember member = marketSupport.findMemberById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_USER_NOT_FOUND));
        Cart cart = marketSupport.findCartByBuyer(member)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_NOT_FOUND));

        ProductInfo product = fetchProduct(request.productId(), productDelayMs);

        int existingQuantity = cart.getItems().stream()
                .filter(item -> item.getProductId().equals(request.productId()))
                .mapToInt(CartItem::getQuantity)
                .sum();

        if (product.availableStock() < existingQuantity + request.quantity()) {
            throw new CustomException(ErrorCode.CART_PRODUCT_OUT_OF_STOCK);
        }

        CartItem cartItem = cart.addItem(request.productId(), request.quantity());
        return CartItemResponse.from(cartItem, product);
    }

    @Transactional(readOnly = true)
    public CartItemListResponse getCqrsCartItems(Long memberId) {
        return cartService.getCartItems(memberId);
    }

    @Transactional
    public CartItemResponse addCqrsCartItem(Long memberId, CartItemAddRequest request) {
        return cartService.addCartItem(memberId, request);
    }

    private CartCqrsExperimentDatasetResponse buildDatasetPlan(
            long baseMemberId,
            List<Long> productIds,
            int readMemberCount,
            int addMemberCountPerScenario
    ) {
        long currentMemberId = baseMemberId;

        List<Long> readMemberIds = range(currentMemberId, readMemberCount);
        currentMemberId += readMemberCount;

        List<Long> syncAddMemberIds = range(currentMemberId, addMemberCountPerScenario);
        currentMemberId += addMemberCountPerScenario;

        List<Long> cqrsAddMemberIds = range(currentMemberId, addMemberCountPerScenario);
        currentMemberId += addMemberCountPerScenario;

        List<Long> syncAddDelayMemberIds = range(currentMemberId, addMemberCountPerScenario);
        currentMemberId += addMemberCountPerScenario;

        List<Long> cqrsAddDelayMemberIds = range(currentMemberId, addMemberCountPerScenario);

        return new CartCqrsExperimentDatasetResponse(
                baseMemberId,
                productIds,
                productIds.get(0),
                readMemberIds,
                syncAddMemberIds,
                cqrsAddMemberIds,
                syncAddDelayMemberIds,
                cqrsAddDelayMemberIds
        );
    }

    private void prepareMembersAndCarts(CartCqrsExperimentDatasetResponse dataset) {
        for (Long memberId : allMemberIds(dataset)) {
            MarketMember member = ensureExperimentMember(memberId);
            Cart cart = ensureCart(member);
            if (cart.hasItems()) {
                cart.clearItems();
            }
        }
    }

    private void seedReadCarts(List<Long> readMemberIds, List<Long> productIds, int quantity) {
        for (Long memberId : readMemberIds) {
            Cart cart = cartRepository.findById(memberId)
                    .orElseThrow(() -> new CustomException(ErrorCode.CART_NOT_FOUND));

            for (Long productId : productIds) {
                cart.addItem(productId, quantity);
            }
        }
    }

    private void upsertCartProductViews(List<Long> productIds) {
        Map<Long, CartProductView> existingViews = cartProductViewRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(CartProductView::getProductId, view -> view));

        List<CartProductView> upsertTargets = new ArrayList<>();
        for (ProductInfo product : productClient.getProducts(productIds)) {
            CartProductView existing = existingViews.get(product.getId());
            if (existing == null) {
                upsertTargets.add(new CartProductView(
                        product.getId(),
                        product.getSellerId(),
                        product.getName(),
                        product.getImageUrl(),
                        product.getPrice(),
                        product.getSalePrice(),
                        product.getStock(),
                        product.getReservedStock(),
                        product.getState(),
                        false
                ));
                continue;
            }

            existing.sync(
                    product.getSellerId(),
                    product.getName(),
                    product.getImageUrl(),
                    product.getPrice(),
                    product.getSalePrice(),
                    product.getStock(),
                    product.getReservedStock(),
                    product.getState(),
                    false
            );
            upsertTargets.add(existing);
        }

        cartProductViewRepository.saveAll(upsertTargets);
        cartProductViewRepository.flush();
    }

    private List<ProductInfo> fetchProducts(List<Long> productIds, long productDelayMs) {
        if (productDelayMs > 0) {
            return productCartCqrsExperimentClient.getProducts(productDelayMs, productIds);
        }
        return productClient.getProducts(productIds);
    }

    private ProductInfo fetchProduct(Long productId, long productDelayMs) {
        List<ProductInfo> products = fetchProducts(List.of(productId), productDelayMs);
        return products.stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.CART_PRODUCT_INFO_NOT_FOUND));
    }

    private MarketMember ensureExperimentMember(Long memberId) {
        return marketMemberRepository.findById(memberId)
                .orElseGet(() -> marketMemberRepository.save(new MarketMember(
                        "cart-cqrs-experiment-" + memberId + "@example.com",
                        "cart-cqrs-experiment-" + memberId,
                        MemberRole.USER,
                        MemberState.ACTIVE,
                        memberId,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )));
    }

    private Cart ensureCart(MarketMember member) {
        return cartRepository.findById(member.getId())
                .orElseGet(() -> cartRepository.save(new Cart(member)));
    }

    private List<Long> allMemberIds(CartCqrsExperimentDatasetResponse dataset) {
        List<Long> memberIds = new ArrayList<>();
        memberIds.addAll(dataset.readMemberIds());
        memberIds.addAll(dataset.syncAddMemberIds());
        memberIds.addAll(dataset.cqrsAddMemberIds());
        memberIds.addAll(dataset.syncAddDelayMemberIds());
        memberIds.addAll(dataset.cqrsAddDelayMemberIds());
        return memberIds;
    }

    private List<Long> range(long startInclusive, int size) {
        List<Long> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(startInclusive + index);
        }
        return values;
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }
}
