package com.yupi.yupao.common;

import com.google.gson.Gson;
import com.yupi.yupao.exception.BusinessException;
import com.yupi.yupao.model.domain.User;
import com.yupi.yupao.model.dto.UserT;

public class test {
    public static void main(String[] args) {
        String jsonStr = "{\n" +
                "\"id\":\"11\",\n" +
                "\"name\":\"dddd\",\n" +
                "\"age\":\"29\"\n" +
                "}";
        if(jsonStr == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Gson gson = new Gson();
        UserT user = gson.fromJson(jsonStr, UserT.class);


    }
}
