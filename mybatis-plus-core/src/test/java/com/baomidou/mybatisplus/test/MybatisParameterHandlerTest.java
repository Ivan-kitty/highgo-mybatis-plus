package com.baomidou.mybatisplus.test;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;


class MybatisParameterHandlerTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Model {

        @TableId(type = IdType.INPUT)
        private Long id;

        private String name;

        @TableField(fill = FieldFill.INSERT)
        private String insertOperator;

        @TableField(fill = FieldFill.UPDATE)
        private String updateOperator;

        Model(String name) {
            this.name = name;
        }

        Model(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Test
    void test() {
        Configuration configuration = new MybatisConfiguration();
        BoundSql boundSql = mock(BoundSql.class);
        StaticSqlSource staticSqlSource = mock(StaticSqlSource.class);
        GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator()).setMetaObjectHandler(new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                setFieldValByName("id", 666L, metaObject);
                setFieldValByName("insertOperator", "??????", metaObject);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                setFieldValByName("updateOperator", "??????", metaObject);
            }
        });
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Model.class);

        Model model = new Model("??????");
        //??????
        MappedStatement mappedStatement = new MappedStatement.Builder(configuration, "***", staticSqlSource, SqlCommandType.SELECT).build();
        new MybatisParameterHandler(mappedStatement, model, boundSql);
        assertThat(model.getId()).isNull();
        assertThat(model.getInsertOperator()).isNull();
        assertThat(model.getUpdateOperator()).isNull();


        // ????????????
        mappedStatement = new MappedStatement.Builder(configuration, "***", staticSqlSource, SqlCommandType.INSERT).build();
        new MybatisParameterHandler(mappedStatement, model, boundSql);
        assertThat(model.getId()).isNotNull();
        assertThat(model.getInsertOperator()).isNotNull();
        assertThat(model.getUpdateOperator()).isNull();


        //map??????
        mappedStatement = new MappedStatement.Builder(configuration, "***", staticSqlSource, SqlCommandType.INSERT).build();
        model = new Model("??????");
        Map<String, Object> params1 = new HashMap<>();
        params1.put(Constants.ENTITY, model);
        new MybatisParameterHandler(mappedStatement, params1, boundSql);
        assertThat(model.getId()).isNotNull();
        assertThat(model.getInsertOperator()).isNotNull();
        assertThat(model.getUpdateOperator()).isNull();
//        //map??????
//        Model model3 = new Model("??????");
//        Map<String, Object> params2 = new HashMap<>();
//        params2.put(Constants.ENTITY, new HashMap<String, Object>() {{
//            put(Constants.MP_OPTLOCK_ET_ORIGINAL, model3);
//        }});
//        MybatisDefaultParameterHandler.processParameter(mappedStatement, params2);
//        Assertions.assertNotNull(model3.getId());
//        Assertions.assertNotNull(model3.getInsertOperator());
//        Assertions.assertNull(model3.getUpdateOperator());


        //????????????
        model = new Model(1L, "??????");
        mappedStatement = new MappedStatement.Builder(configuration, "***", staticSqlSource, SqlCommandType.UPDATE).build();
        new MybatisParameterHandler(mappedStatement, model, boundSql);
        assertThat(model.getId()).isNotNull();
        assertThat(model.getUpdateOperator()).isNotNull();


        //????????????
        List<Model> list = Arrays.asList(new Model("????????????"), new Model("????????????"));
        mappedStatement = new MappedStatement.Builder(configuration, "***", staticSqlSource, SqlCommandType.INSERT).build();
        new MybatisParameterHandler(mappedStatement, list, boundSql);
        list.forEach(m -> {
            assertThat(m.getId()).isNotNull();
            assertThat(m.getInsertOperator()).isNotNull();
            assertThat(m.getUpdateOperator()).isNull();
        });


        //????????????
        mappedStatement = new MappedStatement.Builder(configuration, "***", staticSqlSource, SqlCommandType.UPDATE).build();
        new MybatisParameterHandler(mappedStatement, list, boundSql);
        list.forEach(m -> {
            assertThat(m.getId()).isNotNull();
            assertThat(m.getInsertOperator()).isNotNull();
            assertThat(m.getUpdateOperator()).isNotNull();
        });
    }

}
