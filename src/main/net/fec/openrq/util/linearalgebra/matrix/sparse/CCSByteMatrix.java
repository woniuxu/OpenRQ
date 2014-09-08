/*
 * Copyright 2014 Jose Lopes
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright 2011-2014, by Vladimir Kostyukov and Contributors.
 * 
 * This file is part of la4j project (http://la4j.org)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Contributor(s): Chandler May
 * Maxim Samoylov
 * Anveshi Charuvaka
 * Clement Skau
 * Catherine da Graca
 */
package net.fec.openrq.util.linearalgebra.matrix.sparse;


import static net.fec.openrq.util.arithmetic.OctetOps.aIsEqualToB;
import static net.fec.openrq.util.arithmetic.OctetOps.aIsGreaterThanB;
import static net.fec.openrq.util.arithmetic.OctetOps.aIsLessThanB;
import static net.fec.openrq.util.arithmetic.OctetOps.maxByte;
import static net.fec.openrq.util.arithmetic.OctetOps.minByte;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import net.fec.openrq.util.linearalgebra.LinearAlgebra;
import net.fec.openrq.util.linearalgebra.factory.Factory;
import net.fec.openrq.util.linearalgebra.matrix.ByteMatrices;
import net.fec.openrq.util.linearalgebra.matrix.ByteMatrix;
import net.fec.openrq.util.linearalgebra.matrix.functor.MatrixFunction;
import net.fec.openrq.util.linearalgebra.matrix.functor.MatrixProcedure;
import net.fec.openrq.util.linearalgebra.matrix.source.MatrixSource;
import net.fec.openrq.util.linearalgebra.vector.ByteVector;
import net.fec.openrq.util.linearalgebra.vector.sparse.CompressedByteVector;


/**
 * This is a CCS (Compressed Column Storage) matrix class.
 */
public class CCSByteMatrix extends AbstractCompressedByteMatrix implements SparseByteMatrix {

    private static final long serialVersionUID = 4071505L;

    private static final int MINIMUM_SIZE = 32;

    private byte values[];
    private int rowIndices[];
    private int columnPointers[];


    public CCSByteMatrix() {

        this(0, 0);
    }

    public CCSByteMatrix(int rows, int columns) {

        this(rows, columns, 0);
    }

    public CCSByteMatrix(int rows, int columns, byte array[]) {

        this(ByteMatrices.asArray1DSource(rows, columns, array));
    }

    public CCSByteMatrix(ByteMatrix matrix) {

        this(ByteMatrices.asMatrixSource(matrix));
    }

    public CCSByteMatrix(byte array[][]) {

        this(ByteMatrices.asArray2DSource(array));
    }

    public CCSByteMatrix(MatrixSource source) {

        this(source.rows(), source.columns(), 0);

        for (int j = 0; j < columns; j++) {
            columnPointers[j] = cardinality;
            for (int i = 0; i < rows; i++) {
                byte value = source.get(i, j);
                if (!aIsEqualToB(value, (byte)0)) {

                    if (values.length < cardinality + 1) {
                        growup();
                    }

                    values[cardinality] = value;
                    rowIndices[cardinality] = i;
                    cardinality++;
                }
            }
        }

        columnPointers[columns] = cardinality;
    }

    public CCSByteMatrix(int rows, int columns, int cardinality) {

        super(LinearAlgebra.CCS_FACTORY, rows, columns);
        ensureCardinalityIsCorrect(rows, columns, cardinality);

        int alignedSize = align(cardinality);

        this.cardinality = 0;
        this.values = new byte[alignedSize];
        this.rowIndices = new int[alignedSize];

        this.columnPointers = new int[columns + 1];
    }

    public CCSByteMatrix(int rows,
        int columns,
        int cardinality,
        byte values[],
        int rowIndices[],
        int columnPointers[]) {

        super(LinearAlgebra.CCS_FACTORY, rows, columns);
        ensureCardinalityIsCorrect(rows, columns, cardinality);

        this.cardinality = cardinality;
        this.values = values;
        this.rowIndices = rowIndices;
        this.columnPointers = columnPointers;
    }

    @Override
    public byte safeGet(int i, int j) {

        int k = searchForRowIndex(i, columnPointers[j], columnPointers[j + 1]);

        if (k < columnPointers[j + 1] && rowIndices[k] == i) {
            return values[k];
        }

        return 0;
    }

    @Override
    public void safeSet(int i, int j, byte value) {

        int k = searchForRowIndex(i, columnPointers[j], columnPointers[j + 1]);

        if (k < columnPointers[j + 1] && rowIndices[k] == i) {
            if (aIsEqualToB(value, (byte)0)) {
                remove(k, j);
            }
            else {
                values[k] = value;
            }
        }
        else {
            insert(k, i, j, value);
        }
    }

    @Override
    public ByteVector getColumn(int j) {

        checkColumnBounds(j);

        int columnCardinality = columnPointers[j + 1] - columnPointers[j];

        byte columnValues[] = new byte[columnCardinality];
        int columnIndices[] = new int[columnCardinality];

        System.arraycopy(values, columnPointers[j], columnValues, 0, columnCardinality);
        System.arraycopy(rowIndices, columnPointers[j], columnIndices, 0, columnCardinality);

        return new CompressedByteVector(rows, columnCardinality, columnValues, columnIndices);
    }

    @Override
    public ByteVector getColumn(int j, Factory factory) {

        checkColumnBounds(j);
        ensureFactoryIsNotNull(factory);

        ByteVector result = factory.createVector(rows);

        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            result.set(rowIndices[k], values[k]);
        }

        return result;
    }

    @Override
    public ByteVector getRow(int i, Factory factory) {

        checkRowBounds(i);
        ensureFactoryIsNotNull(factory);

        ByteVector result = factory.createVector(columns);

        int j = 0;
        while (columnPointers[j] < cardinality) {

            int k = searchForRowIndex(i, columnPointers[j], columnPointers[j + 1]);

            if (k < columnPointers[j + 1] && rowIndices[k] == i) {
                result.set(j, values[k]);
            }

            j++;
        }

        return result;
    }

    @Override
    public ByteMatrix copy() {

        byte $values[] = new byte[align(cardinality)];
        int $rowIndices[] = new int[align(cardinality)];
        int $columnPointers[] = new int[columns + 1];

        System.arraycopy(values, 0, $values, 0, cardinality);
        System.arraycopy(rowIndices, 0, $rowIndices, 0, cardinality);
        System.arraycopy(columnPointers, 0, $columnPointers, 0, columns + 1);

        return new CCSByteMatrix(rows, columns, cardinality, $values, $rowIndices, $columnPointers);
    }

    @Override
    public ByteMatrix resize(int rows, int columns) {

        ensureDimensionsAreCorrect(rows, columns);

        if (this.rows == rows && this.columns == columns) {
            return copy();
        }

        if (this.rows >= rows && this.columns >= columns) {

            // TODO: think about cardinality in align call
            byte $values[] = new byte[align(cardinality)];
            int $rowIndices[] = new int[align(cardinality)];
            int $columnPointers[] = new int[columns + 1];

            int $cardinality = 0;

            int k = 0, j = 0;
            while (k < cardinality && j < columns) {

                $columnPointers[j] = $cardinality;

                for (int i = columnPointers[j]; i < columnPointers[j + 1]
                                                && rowIndices[i] < rows; i++, k++) {

                    $values[$cardinality] = values[i];
                    $rowIndices[$cardinality] = rowIndices[i];
                    $cardinality++;
                }
                j++;
            }

            $columnPointers[columns] = $cardinality;

            return new CCSByteMatrix(rows, columns, $cardinality, $values, $rowIndices, $columnPointers);
        }

        if (this.columns < columns) {

            byte $values[] = new byte[align(cardinality)];
            int $rowIndices[] = new int[align(cardinality)];
            int $columnPointers[] = new int[columns + 1];

            System.arraycopy(values, 0, $values, 0, cardinality);
            System.arraycopy(rowIndices, 0, $rowIndices, 0, cardinality);
            System.arraycopy(columnPointers, 0, $columnPointers, 0,
                this.columns + 1);

            for (int i = this.columns; i < columns + 1; i++) {
                $columnPointers[i] = cardinality;
            }

            return new CCSByteMatrix(rows, columns, cardinality, $values, $rowIndices, $columnPointers);
        }

        // TODO: think about cardinality in align call
        byte $values[] = new byte[align(cardinality)];
        int $rowIndices[] = new int[align(cardinality)];
        int $columnPointers[] = new int[columns + 1];

        System.arraycopy(values, 0, $values, 0, cardinality);
        System.arraycopy(rowIndices, 0, $rowIndices, 0, cardinality);
        System.arraycopy(columnPointers, 0, $columnPointers, 0, this.columns + 1);

        return new CCSByteMatrix(rows, columns, cardinality, $values, $rowIndices, $columnPointers);
    }

    @Override
    public boolean nonZeroAt(int i, int j) {

        checkBounds(i, j);
        final int k = searchForRowIndex(i, columnPointers[j], columnPointers[j + 1]);
        return k < columnPointers[j + 1] && rowIndices[k] == i;
    }

    @Override
    public int nonZerosInColumn(int j) {

        checkColumnBounds(j);
        return columnPointers[j + 1] - columnPointers[j];
    }

    @Override
    public int nonZerosInColumn(int j, int fromRow, int toRow) {

        checkColumnBounds(j);
        checkRowRangeBounds(fromRow, toRow);

        int nonZeros = 0;
        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            final int row = rowIndices[k];
            if (fromRow <= row) {
                if (row < toRow) {
                    nonZeros++;
                }
                else {
                    break; // no need to check rows beyond the last one
                }
            }
        }

        return nonZeros;
    }

    @Override
    public void eachNonZero(MatrixProcedure procedure) {

        int nonZeroCount = 0, j = 0;
        while (nonZeroCount < cardinality) {
            for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++, nonZeroCount++) {
                procedure.apply(rowIndices[k], j, values[k]);
            }
            j++;
        }
    }

    @Override
    public void each(MatrixProcedure procedure) {

        int k = 0;
        for (int j = 0; j < columns; j++) {
            int valuesSoFar = columnPointers[j + 1];
            for (int i = 0; i < rows; i++) {
                if (k < valuesSoFar && i == rowIndices[k]) {
                    procedure.apply(i, j, values[k++]);
                }
                else {
                    procedure.apply(i, j, (byte)0);
                }
            }
        }

    }

    @Override
    public void eachInColumn(int j, MatrixProcedure procedure) {

        checkColumnBounds(j);

        int k = columnPointers[j];
        int valuesSoFar = columnPointers[j + 1];
        for (int i = 0; i < rows; i++) {
            if (k < valuesSoFar && i == rowIndices[k]) {
                procedure.apply(i, j, values[k++]);
            }
            else {
                procedure.apply(i, j, (byte)0);
            }
        }
    }

    @Override
    public void eachNonZeroInColumn(int j, MatrixProcedure procedure) {

        checkColumnBounds(j);
        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            procedure.apply(rowIndices[k], j, values[k]);
        }
    }

    @Override
    public void eachNonZeroInColumn(int j, MatrixProcedure procedure, int fromRow, int toRow) {

        checkColumnBounds(j);
        checkRowRangeBounds(fromRow, toRow);

        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            final int row = rowIndices[k];
            if (fromRow <= row) {
                if (row < toRow) {
                    procedure.apply(row, j, values[k]);
                }
                else {
                    break; // no need to check rows beyond the last one
                }
            }
        }
    }

    @Override
    public void safeUpdate(int i, int j, MatrixFunction function) {

        int k = searchForRowIndex(i, columnPointers[j], columnPointers[j + 1]);

        if (k < columnPointers[j + 1] && rowIndices[k] == i) {
            final byte value = function.evaluate(i, j, values[k]);
            if (aIsEqualToB(value, (byte)0)) {
                remove(k, j);
            }
            else {
                values[k] = value;
            }
        }
        else {
            insert(k, i, j, function.evaluate(i, j, (byte)0));
        }
    }

    @Override
    public void updateNonZero(MatrixFunction function) {

        int nonZeroCount = 0, j = 0;
        while (nonZeroCount < cardinality) {
            for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++, nonZeroCount++) {
                final byte value = function.evaluate(rowIndices[k], j, values[k]);
                if (aIsEqualToB(value, (byte)0)) {
                    remove(k, j);
                    // since we removed a nonzero, the indices must be decremented accordingly
                    k--;
                    nonZeroCount--;
                }
                else {
                    values[k] = value;
                }
            }
            j++;
        }
    }

    @Override
    public void updateNonZeroInColumn(int j, MatrixFunction function) {

        checkColumnBounds(j);

        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            final byte value = function.evaluate(rowIndices[k], j, values[k]);
            if (aIsEqualToB(value, (byte)0)) {
                remove(k, j);
                // since we removed a nonzero, the index must be decremented accordingly
                k--;
            }
            else {
                values[k] = value;
            }
        }
    }

    @Override
    public void updateNonZeroInColumn(int j, MatrixFunction function, int fromRow, int toRow) {

        checkColumnBounds(j);
        checkRowRangeBounds(fromRow, toRow);

        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            final int row = rowIndices[k];
            if (fromRow <= row) {
                if (row < toRow) {
                    final byte value = function.evaluate(row, j, values[k]);
                    if (aIsEqualToB(value, (byte)0)) {
                        remove(k, j);
                        // since we removed a nonzero, the index must be decremented accordingly
                        k--;
                    }
                    else {
                        values[k] = value;
                    }
                }
                else {
                    break; // no need to check rows beyond the last one
                }
            }
        }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {

        out.writeInt(rows);
        out.writeInt(columns);
        out.writeInt(cardinality);

        int nonZeroCount = 0, j = 0;
        while (nonZeroCount < cardinality) {
            for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++, nonZeroCount++) {
                out.writeInt(rowIndices[k]);
                out.writeInt(j);
                out.writeByte(values[k]);
            }
            j++;
        }
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {

        rows = in.readInt();
        columns = in.readInt();
        cardinality = in.readInt();

        int alignedSize = align(cardinality);

        values = new byte[alignedSize];
        rowIndices = new int[alignedSize];
        columnPointers = new int[columns + 1];

        for (int k = 0; k < cardinality; k++) {
            rowIndices[k] = in.readInt();
            int j = in.readInt();
            values[k] = in.readByte();
            columnPointers[j + 1] = k + 1;
        }
    }

    private int searchForRowIndex(int i, int left, int right) {

        if (left == right) {
            return left;
        }

        if (right - left < 8) {

            int ii = left;
            while (ii < right && rowIndices[ii] < i) {
                ii++;
            }

            return ii;
        }

        int p = (left + right) / 2;

        if (rowIndices[p] > i) {
            return searchForRowIndex(i, left, p);
        }
        else if (rowIndices[p] < i) {
            return searchForRowIndex(i, p + 1, right);
        }
        else {
            return p;
        }
    }

    private void insert(int k, int i, int j, byte value) {

        // if (Math.abs(value) < Matrices.EPS && value >= 0.0) {
        if (value == 0.0) {
            return;
        }

        if (values.length < cardinality + 1) {
            growup();
        }

        System.arraycopy(values, k, values, k + 1, cardinality - k);
        System.arraycopy(rowIndices, k, rowIndices, k + 1, cardinality - k);

        // for (int k = cardinality; k > position; k--) {
        // values[k] = values[k - 1];
        // rowIndices[k] = rowIndices[k - 1];
        // }

        values[k] = value;
        rowIndices[k] = i;

        for (int jj = j + 1; jj < columns + 1; jj++) {
            columnPointers[jj]++;
        }

        cardinality++;
    }

    private void remove(int k, int j) {

        cardinality--;

        System.arraycopy(values, k + 1, values, k, cardinality - k);
        System.arraycopy(rowIndices, k + 1, rowIndices, k, cardinality - k);

        // for (int kk = k; kk < cardinality; kk++) {
        // values[kk] = values[kk + 1];
        // rowIndices[kk] = rowIndices[kk + 1];
        // }

        for (int jj = j + 1; jj < columns + 1; jj++) {
            columnPointers[jj]--;
        }
    }

    private void growup() {

        if (values.length == capacity()) {
            // This should never happen
            throw new IllegalStateException("This matrix can't grow up.");
        }

        int min = (
                  (rows != 0 && columns > Integer.MAX_VALUE / rows) ?
                                                                   Integer.MAX_VALUE :
                                                                   (rows * columns)
                  );
        int capacity = Math.min(min, (cardinality * 3) / 2 + 1);

        byte $values[] = new byte[capacity];
        int $rowIndices[] = new int[capacity];

        System.arraycopy(values, 0, $values, 0, cardinality);
        System.arraycopy(rowIndices, 0, $rowIndices, 0, cardinality);

        values = $values;
        rowIndices = $rowIndices;
    }

    private int align(int cardinality) {

        return ((cardinality / MINIMUM_SIZE) + 1) * MINIMUM_SIZE;
    }

    @Override
    public byte max() {

        byte max = minByte();

        for (int k = 0; k < cardinality; k++) {
            if (aIsGreaterThanB(values[k], max)) {
                max = values[k];
            }
        }

        if (cardinality == capacity() || aIsGreaterThanB(max, (byte)0)) {
            return max;
        }
        else {
            return 0;
        }
    }

    @Override
    public byte min() {

        byte min = maxByte();

        for (int k = 0; k < cardinality; k++) {
            if (aIsLessThanB(values[k], min)) {
                min = values[k];
            }
        }

        if (cardinality == capacity() || aIsLessThanB(min, (byte)0)) {
            return min;
        }
        else {
            return 0;
        }
    }

    @Override
    public byte maxInColumn(int j) {

        checkColumnBounds(j);

        byte max = minByte();

        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            if (aIsGreaterThanB(values[k], max)) {
                max = values[k];
            }
        }

        if (cardinality == capacity() || aIsGreaterThanB(max, (byte)0)) {
            return max;
        }
        else {
            return 0;
        }
    }

    @Override
    public byte minInColumn(int j) {

        checkColumnBounds(j);

        byte min = minByte();

        for (int k = columnPointers[j]; k < columnPointers[j + 1]; k++) {
            if (aIsLessThanB(values[k], min)) {
                min = values[k];
            }
        }

        if (cardinality == capacity() || aIsLessThanB(min, (byte)0)) {
            return min;
        }
        else {
            return 0;
        }
    }

    /**
     * Returns a CCSMatrix with the selected rows and columns.
     */
    @Override
    public ByteMatrix select(int[] rowIndices, int[] columnIndices) {

        int newRows = rowIndices.length;
        int newCols = columnIndices.length;

        if (newRows == 0 || newCols == 0) {
            fail("No rows or columns selected.");
        }

        // determine number of non-zero values (cardinality)
        // before allocating space, this is perhaps more efficient
        // than single pass and calling grow() when required.
        int newCardinality = 0;
        for (int i = 0; i < newRows; i++) {
            for (int j = 0; j < newCols; j++) {
                if (!aIsEqualToB(get(rowIndices[i], columnIndices[j]), (byte)0)) {
                    newCardinality++;
                }
            }
        }

        // Construct the raw structure for the sparse matrix
        byte[] newValues = new byte[newCardinality];
        int[] newRowIndices = new int[newCardinality];
        int[] newColumnPointers = new int[newCols + 1];

        newColumnPointers[0] = 0;
        int endPtr = 0;
        for (int j = 0; j < newCols; j++) {
            newColumnPointers[j + 1] = newColumnPointers[j];
            for (int i = 0; i < newRows; i++) {
                byte val = get(rowIndices[i], columnIndices[j]);
                if (!aIsEqualToB(val, (byte)0)) {
                    newValues[endPtr] = val;
                    newRowIndices[endPtr] = i;
                    endPtr++;
                    newColumnPointers[j + 1]++;
                }
            }
        }

        return new CCSByteMatrix(newRows, newCols, newCardinality, newValues,
            newRowIndices, newColumnPointers);
    }
}
