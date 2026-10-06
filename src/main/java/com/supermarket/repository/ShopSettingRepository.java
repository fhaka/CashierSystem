package com.supermarket.repository;

import com.supermarket.model.ShopSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopSettingRepository extends JpaRepository<ShopSetting, String> {
}
