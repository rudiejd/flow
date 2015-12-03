package com.vaadin.hummingbird.kernel.change;

import java.util.Objects;

import com.vaadin.hummingbird.kernel.StateNode;

public class ListReplaceChange extends ListChange {

    private Object oldValue;

    public ListReplaceChange(int index, Object oldValue, Object newValue) {
        super(index, newValue);
        this.oldValue = oldValue;
    }

    /**
     * Returns the previous value of the changed object in the list
     *
     * @return the value of the changed object in the list before the change
     */
    public Object getOldValue() {
        return oldValue;
    }

    @Override
    public void accept(StateNode node, NodeChangeVisitor visitor) {
        visitor.visitListReplaceChange(node, this);
    }

    @Override
    public String toString() {
        return "ListReplaceChange [index=" + getIndex() + ", value="
                + getValue() + ", oldValue=" + oldValue + "]";
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && Objects.equals(oldValue, ((ListReplaceChange) obj).oldValue);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 37 + Objects.hashCode(oldValue);
    }

}
