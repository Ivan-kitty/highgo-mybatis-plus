/*
 * Copyright (c) 2011-2021, baomidou (jobob@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baomidou.mybatisplus.extension.plugins;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.parser.ISqlParser;
import com.baomidou.mybatisplus.core.parser.SqlInfo;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.extension.handlers.AbstractSqlParserHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.DialectFactory;
import com.baomidou.mybatisplus.extension.plugins.pagination.DialectModel;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.IDialect;
import com.baomidou.mybatisplus.extension.toolkit.JdbcUtils;
import com.baomidou.mybatisplus.extension.toolkit.PropertyMapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlParserUtils;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.*;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ???????????????
 *
 * @author hubin
 * @since 2016-01-23
 * @deprecated 3.4.0 please use {@link MybatisPlusInterceptor} {@link PaginationInnerInterceptor}
 */
@Setter
@Deprecated
@Accessors(chain = true)
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class PaginationInterceptor extends AbstractSqlParserHandler implements Interceptor {

    protected static final Log logger = LogFactory.getLog(PaginationInterceptor.class);
    /**
     * COUNT SQL ??????
     */
    protected ISqlParser countSqlParser;
    /**
     * ????????????????????????????????????
     */
    protected boolean overflow = false;
    /**
     * ???????????? 500 ???????????? 0 ??? -1 ????????????
     */
    protected long limit = 500L;
    /**
     * ???????????????
     *
     * @since 3.3.1
     */
    private DbType dbType;
    /**
     * ???????????????
     *
     * @since 3.3.1
     */
    private IDialect dialect;
    /**
     * ????????????(????????????,?????????) <br>
     * ????????????????????????????????????????????????????????????????????????????????????????????????????????????
     *
     * @deprecated 3.3.1 {@link #setDbType(DbType)}
     */
    @Deprecated
    protected String dialectType;
    /**
     * ???????????????<br>
     * ??????????????? com.baomidou.mybatisplus.extension.plugins.pagination.dialects.IDialect ???????????????
     *
     * @deprecated 3.3.1 {@link #setDialect(IDialect)}
     */
    @Deprecated
    protected String dialectClazz;

    /**
     * ??????SQL??????Order By
     *
     * @param originalSql ???????????????SQL
     * @param page        page??????
     * @return ignore
     */
    public String concatOrderBy(String originalSql, IPage<?> page) {
        if (CollectionUtils.isNotEmpty(page.orders())) {
            try {
                List<OrderItem> orderList = page.orders();
                Select selectStatement = (Select) CCJSqlParserUtil.parse(originalSql);
                if (selectStatement.getSelectBody() instanceof PlainSelect) {
                    PlainSelect plainSelect = (PlainSelect) selectStatement.getSelectBody();
                    List<OrderByElement> orderByElements = plainSelect.getOrderByElements();
                    List<OrderByElement> orderByElementsReturn = addOrderByElements(orderList, orderByElements);
                    plainSelect.setOrderByElements(orderByElementsReturn);
                    return plainSelect.toString();
                } else if (selectStatement.getSelectBody() instanceof SetOperationList) {
                    SetOperationList setOperationList = (SetOperationList) selectStatement.getSelectBody();
                    List<OrderByElement> orderByElements = setOperationList.getOrderByElements();
                    List<OrderByElement> orderByElementsReturn = addOrderByElements(orderList, orderByElements);
                    setOperationList.setOrderByElements(orderByElementsReturn);
                    return setOperationList.toString();
                } else if (selectStatement.getSelectBody() instanceof WithItem) {
                    // todo: don't known how to resole
                    return originalSql;
                } else {
                    return originalSql;
                }

            } catch (JSQLParserException e) {
                logger.warn("failed to concat orderBy from IPage, exception=" + e.getMessage());
            }
        }
        return originalSql;
    }

    private static List<OrderByElement> addOrderByElements(List<OrderItem> orderList, List<OrderByElement> orderByElements) {
        orderByElements = CollectionUtils.isEmpty(orderByElements) ? new ArrayList<>(orderList.size()) : orderByElements;
        List<OrderByElement> orderByElementList = orderList.stream()
            .filter(item -> StringUtils.isNotBlank(item.getColumn()))
            .map(item -> {
                OrderByElement element = new OrderByElement();
                element.setExpression(new Column(item.getColumn()));
                element.setAsc(item.isAsc());
                element.setAscDescPresent(true);
                return element;
            }).collect(Collectors.toList());
        orderByElements.addAll(orderByElementList);
        return orderByElements;
    }

    /**
     * Physical Page Interceptor for all the queries with parameter {@link RowBounds}
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = PluginUtils.realTarget(invocation.getTarget());
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);

        // SQL ??????
        this.sqlParser(metaObject);

        // ??????????????????SELECT??????  (2019-04-10 00:37:31 ??????????????????)
        MappedStatement mappedStatement = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        if (SqlCommandType.SELECT != mappedStatement.getSqlCommandType()
            || StatementType.CALLABLE == mappedStatement.getStatementType()) {
            return invocation.proceed();
        }

        // ???????????????rowBounds?????????mapper?????????????????????
        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");
        Object paramObj = boundSql.getParameterObject();

        // ????????????????????????page??????
        IPage<?> page = ParameterUtils.findPage(paramObj).orElse(null);

        /*
         * ????????????????????????????????? size ?????? 0 ???????????????
         */
        if (null == page || page.getSize() < 0) {
            return invocation.proceed();
        }

        if (this.limit > 0 && this.limit <= page.getSize()) {
            //????????????????????????
            handlerLimit(page);
        }

        String originalSql = boundSql.getSql();
        Connection connection = (Connection) invocation.getArgs()[0];

        if (page.isSearchCount() && !page.isHitCount()) {
            SqlInfo sqlInfo = SqlParserUtils.getOptimizeCountSql(page.optimizeCountSql(), countSqlParser, originalSql, metaObject);
            this.queryTotal(sqlInfo.getSql(), mappedStatement, boundSql, page, connection);
            if (!this.continueLimit(page)) {
                return null;
            }
        }
        DbType dbType = this.dbType == null ? JdbcUtils.getDbType(connection.getMetaData().getURL()) : this.dbType;
        IDialect dialect = Optional.ofNullable(this.dialect).orElseGet(() -> DialectFactory.getDialect(dbType));
        String buildSql = concatOrderBy(originalSql, page);
        DialectModel model = dialect.buildPaginationSql(buildSql, page.offset(), page.getSize());
        Configuration configuration = mappedStatement.getConfiguration();
        List<ParameterMapping> mappings = new ArrayList<>(boundSql.getParameterMappings());
        Map<String, Object> additionalParameters = (Map<String, Object>) metaObject.getValue("delegate.boundSql.additionalParameters");
        model.consumers(mappings, configuration, additionalParameters);
        metaObject.setValue("delegate.boundSql.sql", model.getDialectSql());
        metaObject.setValue("delegate.boundSql.parameterMappings", mappings);
        return invocation.proceed();
    }

    /**
     * ???????????????????????? Limit ??????
     *
     * @param page ????????????
     * @return
     */
    protected boolean continueLimit(IPage<?> page) {
        if (page.getTotal() <= 0) {
            return false;
        }
        if (page.getCurrent() > page.getPages()) {
            if (this.overflow) {
                //?????????????????????
                handlerOverflow(page);
            } else {
                // ???????????????????????????????????????????????? list ??????
                return false;
            }
        }
        return true;
    }

    /**
     * ??????????????????????????????,?????????????????????
     *
     * @param page IPage
     */
    protected void handlerLimit(IPage<?> page) {
        page.setSize(this.limit);
    }

    /**
     * ?????????????????????
     *
     * @param sql             count sql
     * @param mappedStatement MappedStatement
     * @param boundSql        BoundSql
     * @param page            IPage
     * @param connection      Connection
     * @return true ???????????? false ?????? list ??????
     */
    protected void queryTotal(String sql, MappedStatement mappedStatement, BoundSql boundSql, IPage<?> page, Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            MybatisParameterHandler parameterHandler = new MybatisParameterHandler(mappedStatement, boundSql.getParameterObject(), boundSql);
            parameterHandler.setParameters(statement);
            long total = 0;
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    total = resultSet.getLong(1);
                }
            }
            page.setTotal(total);
        } catch (Exception e) {
            throw ExceptionUtils.mpe("Error: Method queryTotal execution error of sql : \n %s \n", e, sql);
        }
    }

    /**
     * ??????????????????,????????????????????????
     *
     * @param page IPage
     */
    protected void handlerOverflow(IPage<?> page) {
        page.setCurrent(1);
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }

    @Override
    public void setProperties(Properties prop) {
        PropertyMapper.newInstance(prop)
            .whenNotBlack("countSqlParser", ClassUtils::newInstance, this::setCountSqlParser)
            .whenNotBlack("overflow", Boolean::parseBoolean, this::setOverflow)
            .whenNotBlack("dialectType", this::setDialectType)
            .whenNotBlack("dialectClazz", this::setDialectClazz)
            .whenNotBlack("dbType", DbType::getDbType, this::setDbType)
            .whenNotBlack("dialect", ClassUtils::newInstance, this::setDialect)
            .whenNotBlack("limit", Long::parseLong, this::setLimit);
    }

    /**
     * ??????????????????
     *
     * @param dialectType ????????????,?????????
     * @deprecated 3.3.1 {@link #setDbType(DbType)}
     */
    @Deprecated
    public void setDialectType(String dialectType) {
        setDbType(DbType.getDbType(dialectType));
    }

    /**
     * ?????????????????????
     *
     * @param dialectClazz ???????????????
     * @deprecated 3.3.1 {@link #setDialect(IDialect)}}
     */
    @Deprecated
    public void setDialectClazz(String dialectClazz) {
        setDialect(DialectFactory.getDialect(dialectClazz));
    }

}
