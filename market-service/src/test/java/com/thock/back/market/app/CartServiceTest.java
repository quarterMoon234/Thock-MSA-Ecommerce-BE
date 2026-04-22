package com.thock.back.market.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.domain.Cart;
import com.thock.back.market.domain.CartProductView;
import com.thock.back.market.domain.CartProductViewRepository;
import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.in.dto.req.CartItemAddRequest;
import com.thock.back.market.in.dto.res.CartItemListResponse;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @InjectMocks
    private CartService cartService;

    @Mock
    private MarketSupport marketSupport;
    @Mock
    private CartProductViewRepository cartProductViewRepository;

    @Test
    @DisplayName("장바구니 조회 시 CQRS projection에서 상품 정보를 읽는다")
    void getCartItems_readsFromCartProductView() {
        Long memberId = 1L;
        MarketMember buyer = new MarketMember(
                "buyer@test.com",
                "buyer",
                MemberRole.USER,
                MemberState.ACTIVE,
                memberId,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        Cart cart = new Cart(buyer);
        cart.addItem(100L, 2);

        CartProductView productView = new CartProductView(
                100L,
                2L,
                "keyboard",
                "image",
                10000L,
                9000L,
                10,
                3,
                "ON_SALE",
                false
        );

        given(marketSupport.findMemberById(memberId)).willReturn(Optional.of(buyer));
        given(marketSupport.findCartByBuyer(buyer)).willReturn(Optional.of(cart));
        given(cartProductViewRepository.findAllByProductIdIn(List.of(100L))).willReturn(List.of(productView));

        CartItemListResponse response = cartService.getCartItems(memberId);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productId()).isEqualTo(100L);
        assertThat(response.items().get(0).productName()).isEqualTo("keyboard");
        assertThat(response.items().get(0).stock()).isEqualTo(7);
        verify(marketSupport, never()).getProducts(List.of(100L));
    }

    @Test
    @DisplayName("장바구니 추가 시 예약 재고를 제외한 주문 가능 재고를 기준으로 검증한다")
    void addCartItem_reservedStockIncluded_throwsOutOfStock() {
        Long memberId = 1L;
        MarketMember buyer = new MarketMember(
                "buyer@test.com",
                "buyer",
                MemberRole.USER,
                MemberState.ACTIVE,
                memberId,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        Cart cart = new Cart(buyer);
        cart.addItem(100L, 4);

        CartProductView productView = new CartProductView(
                100L,
                2L,
                "keyboard",
                "image",
                10000L,
                9000L,
                5,
                1,
                "ON_SALE",
                false
        );

        given(marketSupport.findMemberById(memberId)).willReturn(Optional.of(buyer));
        given(marketSupport.findCartByBuyer(buyer)).willReturn(Optional.of(cart));
        given(cartProductViewRepository.findByProductId(100L)).willReturn(Optional.of(productView));

        assertThatThrownBy(() -> cartService.addCartItem(memberId, new CartItemAddRequest(100L, 1)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customException = (CustomException) ex;
                    org.assertj.core.api.Assertions.assertThat(customException.getErrorCode())
                            .isEqualTo(ErrorCode.CART_PRODUCT_OUT_OF_STOCK);
                });
        verify(marketSupport, never()).getProduct(100L);
    }
}
