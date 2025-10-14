package group.gnometrading.gateways.inbound;

import group.gnometrading.schemas.Schema;
import group.gnometrading.utils.Copyable;
import group.gnometrading.utils.Resettable;

public interface Book<T extends Schema> extends Comparable<Book<T>>, Copyable<Book<T>>, Resettable {
    /**
     * Get the sequence number of the book.
     * @return the sequence number
     */
    long getSequenceNumber();

    /**
     * Write the book to the schema's encoder.
     * @param schema the schema to write
     */
    void writeTo(T schema);

    /**
     * Update the book from the schema's decoder.
     * @param schema the schema to read
     */
    void updateFrom(T schema);
}
