package com.customerservice.mapper;

import com.customerservice.model.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {

    Customer selectById(@Param("id") Long id);

    Customer selectByCustomerUid(@Param("customerUid") String customerUid);

    int insert(Customer customer);

    int update(Customer customer);
}
