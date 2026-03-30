package com.thock.back.product.out;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.thock.back.product.domain.entity.QProduct;
import com.thock.back.product.in.dto.ProductSearchResponse;
import com.thock.back.product.in.dto.ProductSearchRequest;
import com.thock.back.product.in.dto.ProductSearchSortType;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

public class ProductRepositoryImpl implements ProductRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    public ProductRepositoryImpl(EntityManager em) {
        this.queryFactory = new JPAQueryFactory(em);
    }

    @Override
    public Page<ProductSearchResponse> search(ProductSearchRequest condition, Pageable pageable) {
        QProduct product = QProduct.product;

        BooleanBuilder builder = new BooleanBuilder();

        if (condition != null) {
            if (condition.keyword() != null && !condition.keyword().isBlank()) {
                builder.and(product.name.containsIgnoreCase(condition.keyword()));
            }
            if (condition.category() != null) {
                builder.and(product.category.eq(condition.category()));
            }
            if (condition.minPrice() != null) {
                builder.and(product.price.goe(condition.minPrice()));
            }
            if (condition.maxPrice() != null) {
                builder.and(product.price.loe(condition.maxPrice()));
            }
            if (condition.state() != null) {
                builder.and(product.state.eq(condition.state()));
            }
        }

        List<ProductSearchResponse> content = queryFactory
                .select(Projections.constructor(
                        ProductSearchResponse.class,
                        product.id,
                        product.name,
                        product.imageUrl,
                        product.price,
                        Expressions.stringTemplate("concat('판매자 ', {0})", product.sellerId.stringValue())
                ))
                .from(product)
                .where(builder)
                .orderBy(getOrderSpecifier(product, condition))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(product.count())
                .from(product)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private OrderSpecifier<?> getOrderSpecifier(QProduct product, ProductSearchRequest condition) {
        ProductSearchSortType sortType =
                condition == null || condition.sort() == null
                        ? ProductSearchSortType.LATEST
                        : condition.sort();

        return switch (sortType) {
            case PRICE_ASC -> product.price.asc();
            case PRICE_DESC -> product.price.desc();
            case LATEST -> product.id.desc();
        };
    }
}
