package com.customerservice.model.enums;

public enum VipLevel {
    NORMAL(0),
    SILVER(10),
    GOLD(20),
    DIAMOND(30);

    private final int priorityWeight;

    VipLevel(int priorityWeight) {
        this.priorityWeight = priorityWeight;
    }

    public int getPriorityWeight() {
        return priorityWeight;
    }
}
