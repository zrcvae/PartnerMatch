package com.yupi.yupao.model.domain;

public class test {
    public static void main(String[] args) {
        Team team = new Team();
        team.setUserId(1L);
        User user = new User();
        user.setId(1L);
        if(user.getId() == team.getUserId()){
            System.out.println(11);
        }else {
            System.out.println(22);
        }
    }
}
