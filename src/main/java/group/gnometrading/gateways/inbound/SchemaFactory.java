package group.gnometrading.gateways.inbound;

import group.gnometrading.schemas.Schema;

public interface SchemaFactory<T extends Schema> {
    /**
     * Create an array of schemas.
     *
     * @param size the size of the array
     * @return the array of schemas
     */
     T[] createSchemaArray(int size);

    /**
     * Create a new schema object.
     *
     * @return the new schema object
     */
    T createSchema();

    /**
     * Create an array of books.
     *
     * @param size the size of the array
     * @return the array of books
     */
    Book<T>[] createBookArray(int size);

    /**
     * Create a new book object.
     *
     * @return the new book object
     */
    Book<T> createBook();
}
