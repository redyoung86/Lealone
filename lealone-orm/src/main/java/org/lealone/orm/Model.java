/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.orm;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.New;
import org.lealone.db.index.Index;
import org.lealone.db.result.Result;
import org.lealone.db.result.SelectOrderBy;
import org.lealone.db.table.Column;
import org.lealone.db.table.Table;
import org.lealone.db.table.TableFilter;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueInt;
import org.lealone.db.value.ValueLong;
import org.lealone.orm.property.PBaseNumber;
import org.lealone.orm.property.TQProperty;
import org.lealone.sql.dml.Delete;
import org.lealone.sql.dml.Insert;
import org.lealone.sql.dml.Select;
import org.lealone.sql.dml.Update;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ExpressionColumn;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.expression.Wildcard;
import org.lealone.sql.expression.aggregate.Aggregate;

import com.fasterxml.jackson.databind.JsonNode;

import io.vertx.core.json.JsonObject;

/**
 * Base root model bean.
 * <p>
 * With code generation for each table a model bean is created that extends this.
 * <p>
 * Provides common features for all root model beans
 * </p>
 *
 * @param <T> the model bean type 
 */
@SuppressWarnings("rawtypes")
public abstract class Model<T> {

    private static final Logger logger = LoggerFactory.getLogger(Model.class);

    public class PRowId extends PBaseNumber<T, Long> {

        private long value;

        /**
         * Construct with a property name and root instance.
         *
         * @param name property name
         * @param root the root model bean instance
         */
        public PRowId(String name, T root) {
            super(name, root);
        }

        /**
         * Construct with additional path prefix.
         */
        public PRowId(String name, T root, String prefix) {
            super(name, root, prefix);
        }

        // 不需要通过外部设置
        T set(long value) {
            if (!areEqual(this.value, value)) {
                this.value = value;
                expr().set(name, ValueLong.get(value));
            }
            return root;
        }

        @Override
        public final T deserialize(HashMap<String, Value> map) {
            Value v = map.get(name);
            if (v != null) {
                value = v.getLong();
            }
            return root;
        }

        public final long get() {
            return value;
        }

    }

    public static class NVPair {
        public final String name;
        public final Value value;

        public NVPair(String name, Value value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((name == null) ? 0 : name.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NVPair other = (NVPair) obj;
            if (name == null) {
                if (other.name != null)
                    return false;
            } else if (!name.equals(other.name))
                return false;
            return true;
        }

    }

    @SuppressWarnings("unchecked")
    public final PRowId _rowid_ = new PRowId(Column.ROWID, (T) this);

    private final ModelTable modelTable;

    /**
     * The root model bean instance. Used to provide fluid query construction.
     */
    private T root;

    // 以下字段不是必须的，所以延迟初始化，避免浪费不必要的内存
    private HashSet<NVPair> nvPairs;
    private ArrayList<Expression> selectExpressions;
    private ArrayList<Expression> groupExpressions;
    private ExpressionBuilder<T> having;
    private ExpressionBuilder<T> whereExpressionBuilder;

    /**
    * The underlying expression builders held as a stack. Pushed and popped based on and/or (conjunction/disjunction).
    */
    private ArrayStack<ExpressionBuilder<T>> expressionBuilderStack;

    boolean isDao;
    TQProperty[] tqProperties;

    public Model(ModelTable table, boolean isDao) {
        this.modelTable = table;
        this.isDao = isDao;
    }

    ModelTable getTable() {
        return modelTable;
    }

    public boolean isDao() {
        return isDao;
    }

    protected void setTQProperties(TQProperty[] tqProperties) {
        this.tqProperties = tqProperties;
    }

    /**
     * Sets the root model bean instance. Used to provide fluid query construction.
     */
    protected void setRoot(T root) {
        this.root = root;
    }

    void addNVPair(String name, Value value) {
        if (nvPairs == null) {
            nvPairs = new HashSet<>();
        }
        nvPairs.add(new NVPair(name, value));
    }

    private void reset() {
        nvPairs = null;
        selectExpressions = null;
        groupExpressions = null;
        having = null;
        whereExpressionBuilder = null;
        expressionBuilderStack = null;
    }

    private ExpressionBuilder<T> getWhereExpressionBuilder() {
        if (whereExpressionBuilder == null) {
            whereExpressionBuilder = new ExpressionBuilder<T>(this);
        }
        return whereExpressionBuilder;
    }

    private ArrayList<Expression> getSelectExpressions() {
        if (selectExpressions == null) {
            selectExpressions = New.arrayList();
        }
        return selectExpressions;
    }

    public String getDatabaseName() {
        return modelTable.getDatabaseName();
    }

    public String getSchemaName() {
        return modelTable.getSchemaName();
    }

    public String getTableName() {
        return modelTable.getTableName();
    }

    @SafeVarargs
    public final T select(TQProperty<?>... properties) {
        selectExpressions = new ArrayList<>();
        for (TQProperty<?> p : properties) {
            ExpressionColumn c = getExpressionColumn(p);
            selectExpressions.add(c);
        }
        return root;
    }

    public T orderBy() {
        getStack().pop();
        pushExprBuilder(getWhereExpressionBuilder());
        return root;
    }

    @SafeVarargs
    public final T groupBy(TQProperty<?>... properties) {
        groupExpressions = new ArrayList<>();
        for (TQProperty<?> p : properties) {
            ExpressionColumn c = getExpressionColumn(p);
            groupExpressions.add(c);
        }

        return root;
    }

    static ExpressionColumn getExpressionColumn(TQProperty<?> p) {
        return new ExpressionColumn(p.getDatabaseName(), p.getSchemaName(), p.getTableName(), p.getName());
    }

    ExpressionColumn getExpressionColumn(String propertyName) {
        return new ExpressionColumn(modelTable.getDatabase(), modelTable.getSchemaName(), modelTable.getTableName(),
                propertyName);
    }

    public T having() {
        getStack().pop();
        having = new ExpressionBuilder<>(this);
        pushExprBuilder(having);
        return root;
    }

    public T or() {
        peekExprBuilder().or();
        return root;
    }

    public <M> M or(Model<M> m) {
        return m.root;
    }

    public T and() {
        peekExprBuilder().and();
        return root;
    }

    public <M> M and(Model<M> m) {
        return m.root;
    }

    public void printSQL() {
        StringBuilder sql = new StringBuilder();
        if (selectExpressions != null) {
            sql.append("SELECT (").append(selectExpressions.get(0).getSQL());
            for (int i = 1, size = selectExpressions.size(); i < size; i++)
                sql.append(", ").append(selectExpressions.get(i).getSQL());
            sql.append(") FROM ").append(modelTable.getTableName());
        }
        if (whereExpressionBuilder != null) {
            sql.append("\r\n  WHERE ").append(whereExpressionBuilder.getExpression().getSQL());
        }
        if (groupExpressions != null) {
            sql.append("\r\n  GROUP BY (").append(groupExpressions.get(0).getSQL());
            for (int i = 1, size = groupExpressions.size(); i < size; i++)
                sql.append(", ").append(groupExpressions.get(i).getSQL());
            sql.append(")");
            if (having != null) {
                sql.append(" HAVING ").append(having.getExpression().getSQL());
            }
        }
        if (whereExpressionBuilder != null) {
            ArrayList<SelectOrderBy> list = whereExpressionBuilder.getOrderList();
            if (list != null && !list.isEmpty()) {
                sql.append("\r\n  ORDER BY (").append(list.get(0).getSQL());
                for (int i = 1, size = list.size(); i < size; i++)
                    sql.append(", ").append(list.get(i).getSQL());
                sql.append(")");
            }
        }
        // reset();
        System.out.println(sql);
    }

    // TODO
    public T not() {
        pushExprBuilder(peekExprBuilder().not());
        return root;
    }

    private void checkDao(String methodName) {
        if (!isDao) {
            throw new UnsupportedOperationException("The " + methodName + " operation is not allowed, please use "
                    + this.getClass().getSimpleName() + ".dao." + methodName + "() instead.");
        }
    }

    public T where() {
        return root;
    }

    public <M> M where(Model<M> m) {
        return m.root;
    }

    /**
     * Execute the query returning either a single bean or null (if no matching bean is found).
     */
    public T findOne() {
        checkDao("findOne");
        Select select = createSelect();
        select.setLimit(ValueExpression.get(ValueInt.get(1)));
        select.init();
        select.prepare();
        logger.info("execute sql: " + select.getPlanSQL());
        Result result = select.executeQuery(1);
        result.next();
        reset();
        return deserialize(result);
    }

    private Select createSelect() {
        Select select = new Select(modelTable.getSession());
        TableFilter tableFilter = new TableFilter(modelTable.getSession(), modelTable.getTable(), null, true, null);
        select.addTableFilter(tableFilter, true);
        if (selectExpressions == null) {
            getSelectExpressions().add(new Wildcard(null, null));
        }
        selectExpressions.add(getExpressionColumn(Column.ROWID)); // 总是获取rowid
        select.setExpressions(selectExpressions);
        if (whereExpressionBuilder != null)
            select.addCondition(whereExpressionBuilder.getExpression());
        if (groupExpressions != null) {
            select.setGroupQuery();
            select.setGroupBy(groupExpressions);
            select.setHaving(having.getExpression());
        }
        if (whereExpressionBuilder != null)
            select.setOrder(whereExpressionBuilder.getOrderList());

        return select;
    }

    @SuppressWarnings("unchecked")
    private T deserialize(Result result) {
        Value[] row = result.currentRow();
        if (row == null)
            return null;

        int len = row.length;
        HashMap<String, Value> map = new HashMap<>(len);
        for (int i = 0; i < len; i++) {
            map.put(result.getColumnName(i), row[i]);
        }

        Model m = newInstance(modelTable);
        if (m != null) {
            for (TQProperty p : m.tqProperties) {
                p.deserialize(map);
            }
            m._rowid_.deserialize(map);
        }
        return (T) m;
    }

    protected Model newInstance(ModelTable t) {
        return null;
    }

    protected void deserialize(JsonNode node) {
        for (TQProperty p : tqProperties) {
            p.deserialize(node);
        }
    }

    /**
     * Execute the query returning the list of objects.
     */
    public List<T> findList() {
        checkDao("findList");
        Select select = createSelect();
        select.init();
        select.prepare();
        logger.info("execute sql: " + select.getPlanSQL());
        Result result = select.executeQuery(-1);
        reset();
        ArrayList<T> list = new ArrayList<>(result.getRowCount());
        while (result.next()) {
            list.add(deserialize(result));
        }
        return list;
    }

    /**
     * Return the count of entities this query should return.
     */
    public int findCount() {
        checkDao("findCount");
        Select select = createSelect();
        select.setGroupQuery();
        getSelectExpressions().clear();
        Aggregate a = new Aggregate(Aggregate.COUNT_ALL, null, select, false);
        getSelectExpressions().add(a);
        select.setExpressions(getSelectExpressions());
        select.init();
        select.prepare();
        logger.info("execute sql: " + select.getPlanSQL());
        Result result = select.executeQuery(-1);
        reset();
        result.next();
        return result.currentRow()[0].getInt();
    }

    public long insert() {
        // TODO 是否允许通过 XXX.dao来insert记录?
        if (isDao) {
            String name = this.getClass().getSimpleName();
            throw new UnsupportedOperationException("The insert operation is not allowed for " + name
                    + ".dao,  please use new " + name + "().insert() instead.");
        }
        Table dbTable = modelTable.getTable();
        Insert insert = new Insert(modelTable.getSession());
        int size = nvPairs.size();
        Column[] columns = new Column[size];
        Expression[] expressions = new Expression[size];
        int i = 0;
        for (NVPair p : nvPairs) {
            columns[i] = dbTable.getColumn(p.name);
            expressions[i] = ValueExpression.get(p.value);
            i++;
        }
        insert.setColumns(columns);
        insert.addRow(expressions);
        insert.setTable(dbTable);
        insert.prepare();
        logger.info("execute sql: " + insert.getPlanSQL());
        insert.executeUpdate();
        long rowId = modelTable.getSession().getLastRowKey();
        _rowid_.set(rowId);
        commit();
        reset();
        return rowId;
    }

    public int update() {
        Table dbTable = modelTable.getTable();
        Update update = new Update(modelTable.getSession());
        TableFilter tableFilter = new TableFilter(modelTable.getSession(), dbTable, null, true, null);
        update.setTableFilter(tableFilter);
        checkWhereExpression(dbTable, "update");
        if (whereExpressionBuilder != null)
            update.setCondition(whereExpressionBuilder.getExpression());
        for (NVPair p : nvPairs) {
            update.setAssignment(dbTable.getColumn(p.name), ValueExpression.get(p.value));
        }
        update.prepare();
        reset();
        logger.info("execute sql: " + update.getPlanSQL());
        int count = update.executeUpdate();
        commit();
        return count;
    }

    public int delete() {
        Table dbTable = modelTable.getTable();
        Delete delete = new Delete(modelTable.getSession());
        TableFilter tableFilter = new TableFilter(modelTable.getSession(), dbTable, null, true, null);
        delete.setTableFilter(tableFilter);
        checkWhereExpression(dbTable, "delete");
        if (whereExpressionBuilder != null)
            delete.setCondition(whereExpressionBuilder.getExpression());
        delete.prepare();
        reset();
        logger.info("execute sql: " + delete.getPlanSQL());
        int count = delete.executeUpdate();
        commit();
        return count;
    }

    private void checkWhereExpression(Table dbTable, String methodName) {
        if (whereExpressionBuilder == null || whereExpressionBuilder.getExpression() == null) {
            maybeCreateWhereExpression(dbTable);
            if (whereExpressionBuilder == null || whereExpressionBuilder.getExpression() == null) {
                checkDao(methodName);
            }
        } else {
            checkDao(methodName);
        }
    }

    private void maybeCreateWhereExpression(Table dbTable) {
        // 没有指定where条件时，如果存在ROWID，则用ROWID当where条件
        if (_rowid_.get() != 0) {
            peekExprBuilder().eq(Column.ROWID, _rowid_.get());
        } else {
            Index primaryKey = dbTable.findPrimaryKey();
            if (primaryKey != null) {
                for (Column c : primaryKey.getColumns()) {
                    // 如果主键由多个字段组成，当前面的字段没有指定时就算后面的指定了也不用它们来生成where条件
                    boolean found = false;
                    for (NVPair p : nvPairs) {
                        if (dbTable.getDatabase().equalsIdentifiers(p.name, c.getName())) {
                            peekExprBuilder().eq(p.name, p.value);
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        break;
                }
            }
        }
    }

    private void commit() {
        modelTable.getSession().commit();
    }

    private ArrayStack<ExpressionBuilder<T>> getStack() {
        if (expressionBuilderStack == null) {
            expressionBuilderStack = new ArrayStack<ExpressionBuilder<T>>();
            expressionBuilderStack.push(getWhereExpressionBuilder());
        }
        return expressionBuilderStack;
    }

    /**
     * Push the expression builder onto the appropriate stack.
     */
    private T pushExprBuilder(ExpressionBuilder<T> builder) {
        getStack().push(builder);
        return root;
    }

    /**
     * Return the current expression builder that expressions should be added to.
     */
    public ExpressionBuilder<T> peekExprBuilder() {
        return getStack().peek();
    }

    @Override
    public String toString() {
        JsonObject json = JsonObject.mapFrom(this);
        return json.encode();
    }

    public T lp() {
        ExpressionBuilder<T> e = new ExpressionBuilder<>(this);
        pushExprBuilder(e);
        return root;
    }

    public T leftParenthesis() {
        return lp();
    }

    public T rp() {
        ExpressionBuilder<T> right = getStack().pop();
        ExpressionBuilder<T> left = peekExprBuilder();
        left.junction(right);
        return root;
    }

    public T rightParenthesis() {
        return rp();
    }

    public T join(Model<?> m) {
        return root;
    }

    public T on() {
        return root;
    }

    /**
    * Stack based on ArrayList.
    *
    * @author rbygrave
    */
    static class ArrayStack<E> {

        private final List<E> list;

        /**
        * Creates an empty Stack with an initial size.
        */
        public ArrayStack(int size) {
            this.list = new ArrayList<>(size);
        }

        /**
        * Creates an empty Stack.
        */
        public ArrayStack() {
            this.list = new ArrayList<>();
        }

        @Override
        public String toString() {
            return list.toString();
        }

        /**
        * Pushes an item onto the top of this stack.
        */
        public void push(E item) {
            list.add(item);
        }

        /**
        * Removes the object at the top of this stack and returns that object as
        * the value of this function.
        */
        public E pop() {
            int len = list.size();
            if (len == 0) {
                throw new EmptyStackException();
            }
            return list.remove(len - 1);
        }

        private E peekZero(boolean retNull) {
            int len = list.size();
            if (len == 0) {
                if (retNull) {
                    return null;
                }
                throw new EmptyStackException();
            }
            return list.get(len - 1);
        }

        /**
        * Returns the object at the top of this stack without removing it.
        */
        public E peek() {
            return peekZero(false);
        }

        /**
        * Returns the object at the top of this stack without removing it.
        * If the stack is empty this returns null.
        */
        public E peekWithNull() {
            return peekZero(true);
        }

        /**
        * Tests if this stack is empty.
        */
        public boolean isEmpty() {
            return list.isEmpty();
        }

        public int size() {
            return list.size();
        }

        public boolean contains(E o) {
            return list.contains(o);
        }
    }
}
