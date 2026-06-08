package com.cs.alloc.mapper;

import com.cs.alloc.domain.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {
    Customer selectById(@Param("id") Long id);
    void insert(Customer customer);
    void update(Customer customer);
}
