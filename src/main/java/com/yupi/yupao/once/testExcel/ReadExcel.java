package com.yupi.yupao.once.testExcel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.metadata.ReadSheet;
import com.alibaba.excel.util.ListUtils;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * @author zrc
 * @date 2024/05/27
 */
public class ReadExcel {


    /**
     * 最简单的读取excel
     * @param args
     */
    public static void main(String[] args) {
        String fileName = "D:\\JavaTest\\yupi-item\\yupao-backend-master\\src\\main\\resources\\testExcel.xlsx";
        synchronousRead(fileName);
    }

    /**
     * 最简单的读取
     */
    public static void easyRead(String fileName){
        EasyExcel.read(fileName, testUserInfo.class, new TestDataListener()).sheet().doRead();
    }

    /**
     * 同步读取多条
     * 会比较耗时
     */
    public static void synchronousRead(String fileName) {

        // 这里 需要指定读用哪个class去读，然后读取第一个sheet 同步读取会自动finish
        List<testUserInfo> list = EasyExcel.read(fileName).head(testUserInfo.class).sheet().doReadSync();
        list.stream().forEach(user ->{
            System.out.println("读取到的用户名为：" + user.getUsername() + "用户编号为:" + user.getPlanetCode());
        });
    }
}
