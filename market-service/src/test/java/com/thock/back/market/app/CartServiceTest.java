package com.thock.back.market.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.domain.Cart;
import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.in.dto.req.CartItemAddRequest;
import com.thock.back.market.out.api.dto.ProductInfo;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @InjectMocks
    private CartService cartService;

    @Mock
    private MarketSupport marketSupport;

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

        ProductInfo product = new ProductInfo(
                100L,
                2L,
                "keyboard",
                "image",
                10000L,
                9000L,
                5,
                1,
                "ON_SALE"
        );

        given(marketSupport.findMemberById(memberId)).willReturn(Optional.of(buyer));
        given(marketSupport.findCartByBuyer(buyer)).willReturn(Optional.of(cart));
        given(marketSupport.getProduct(100L)).willReturn(product);

        assertThatThrownBy(() -> cartService.addCartItem(memberId, new CartItemAddRequest(100L, 1)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customException = (CustomException) ex;
                    org.assertj.core.api.Assertions.assertThat(customException.getErrorCode())
                            .isEqualTo(ErrorCode.CART_PRODUCT_OUT_OF_STOCK);
                });
    }
}
