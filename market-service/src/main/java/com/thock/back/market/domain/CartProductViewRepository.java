package com.thock.back.market.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CartProductViewRepository extends JpaRepository<CartProductView, Long> {

    Optional<CartProductView> findByProductId(Long productId);

    List<CartProductView> findAllByProductIdIn(Collection<Long> productIds);
}
