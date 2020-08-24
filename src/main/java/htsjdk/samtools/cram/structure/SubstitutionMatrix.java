/**
 * ****************************************************************************
 * Copyright 2013 EMBL-EBI
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ****************************************************************************
 */
package htsjdk.samtools.cram.structure;

import htsjdk.samtools.cram.encoding.readfeatures.ReadFeature;
import htsjdk.samtools.cram.encoding.readfeatures.Substitution;
import htsjdk.samtools.util.Log;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Substitution matrix, used to represent base substitutions for reference-based CRAM
 * compression.
 *
 * The matrix is stored internally in two forms; the packed/encoded form used for serialization,
 * and an expanded in-memory form used for fast bi-directional interconversion between bases and
 * substitution codes during read and write.
 *
 * This implementation allows both upper and lower case versions of a given reference base
 * to be substituted with the same (upper case) substitute base (except for 'N', which is only
 * handled for upper case) although it does not *generate* substitutions for lower case reference
 * bases.
 */
 public class SubstitutionMatrix {
    private static final Log log = Log.getInstance(Substitution.class);

    // substitution bases, in the order in which they're stored in the substitution matrix
    private static List<SubstitutionBase> BASES = Arrays.asList(SubstitutionBase.values());

    // Cache this value eliminate the need to repeatedly retrieve the SubstitutionBase enum's
    // values array just to determine it's size.
    public static final int BASES_SIZE = BASES.size();

    private static final byte NO_BASE = 0;

    // Since bases are represented as bytes, there are theoretically 256 possible symbols in the
    // symbol space, though in reality we only care about 10 of these (5 upper and 5 lower case
    // reference bases), drawn from positive values.
    static final int SYMBOL_SPACE_SIZE = 128;

    // number of possible substitution codes per base
    private static final int CODES_PER_BASE = BASES_SIZE - 1;

    // The substitution "matrix" in serialized form, encoded in a 5 byte vector, one byte for
    // each base in the set of possible substitution bases { A, C, G, T and N }, in that order.
    // Each byte in turn represents a packed bit vector of substitution codes (values 0-3, encoded
    // in 2 bits each), one for each possible substitution of that base with another base from the
    // same set, in the same order. The allows the most frequent substitution codes(s) to have the
    // shortest prefix(es) when represented as ITF8 in the context of serialized read features.
    private byte[] encodedMatrixBytes = new byte[BASES_SIZE];

    // The expanded in-memory matrix of substitution codes. In order to enable quick inter-conversion
    // between bases and substitution codes, we use a full square but sparse matrix that covers
    // the entire symbol space in order to allow them to be directly indexed by (refBase, readBase)
    // pairs. Note: since this implementation doesn't *generate* substitutions for lower case bases,
    // this array can only be used to find a substitution code for lower case reference bases.
    private final byte[][] codeByBase = new byte[SYMBOL_SPACE_SIZE][SYMBOL_SPACE_SIZE];

    // The expanded in-memory matrix of substitute bases, indexed by (refBase, code) pairs, used when
    // reading cram records. Note that this array can be indexed using upper or lower case bases to
    // handle lower case reference substitutions that may have been generated by other CRAM writers.
    private final byte[][] baseByCode = new byte[SYMBOL_SPACE_SIZE][SYMBOL_SPACE_SIZE];

    /**
     * Create a SubstitutionMatrix given a list of CramCompressionRecord
     * @param records array of CramCompressionRecord with Substitutions
     */
    public SubstitutionMatrix(final List<CRAMCompressionRecord> records) {
        final long[][] frequencies = buildFrequencies(records);
        for (final SubstitutionBase b : BASES) {
            // substitutionCodeVector has a side effect of updating codeByBase
            encodedMatrixBytes[b.ordinal()] = substitutionCodeVector(b.getBase(), frequencies[b.getBase()]);
        }

        for (final SubstitutionBase r : BASES) {
            for (final SubstitutionBase b : BASES) {
                if (r != b) {
                    baseByCode[r.getBase()][codeByBase[r.getBase()][b.getBase()]] = b.getBase();
                    // propagate the same code to lower case reference bases
                    baseByCode[Character.toLowerCase(r.getBase())][codeByBase[r.getBase()][b.getBase()]] = b.getBase();
                }
            }
        }
    }

    /**
     * Create a SubstitutionMatrix from a serialized byte array
     * @param matrix serialized substitution matrix from a CRAM stream
     */
    public SubstitutionMatrix(final byte[] matrix) {
        this.encodedMatrixBytes = matrix;

        // unpack the substitution code vectors and populate the base substitutions lookup
        // matrix using the unpacked substitution codes
        baseByCode['A'][(encodedMatrixBytes[0] >> 6) & 3] = 'C';
        baseByCode['A'][(encodedMatrixBytes[0] >> 4) & 3] = 'G';
        baseByCode['A'][(encodedMatrixBytes[0] >> 2) & 3] = 'T';
        baseByCode['A'][(encodedMatrixBytes[0]) & 3] = 'N';
        // propagate to lower case 'a'
        System.arraycopy(baseByCode['A'], 0, baseByCode['a'], 0, 4);

        baseByCode['C'][(encodedMatrixBytes[1] >> 6) & 3] = 'A';
        baseByCode['C'][(encodedMatrixBytes[1] >> 4) & 3] = 'G';
        baseByCode['C'][(encodedMatrixBytes[1] >> 2) & 3] = 'T';
        baseByCode['C'][(encodedMatrixBytes[1]) & 3] = 'N';
        // propagate to lower case 'c'
        System.arraycopy(baseByCode['C'], 0, baseByCode['c'], 0, 4);

        baseByCode['G'][(encodedMatrixBytes[2] >> 6) & 3] = 'A';
        baseByCode['G'][(encodedMatrixBytes[2] >> 4) & 3] = 'C';
        baseByCode['G'][(encodedMatrixBytes[2] >> 2) & 3] = 'T';
        baseByCode['G'][(encodedMatrixBytes[2]) & 3] = 'N';
        // propagate to lower case 'g'
        System.arraycopy(baseByCode['G'], 0, baseByCode['g'], 0, 4);

        baseByCode['T'][(encodedMatrixBytes[3] >> 6) & 3] = 'A';
        baseByCode['T'][(encodedMatrixBytes[3] >> 4) & 3] = 'C';
        baseByCode['T'][(encodedMatrixBytes[3] >> 2) & 3] = 'G';
        baseByCode['T'][(encodedMatrixBytes[3]) & 3] = 'N';
        // propagate to lower case 't'
        System.arraycopy(baseByCode['T'], 0, baseByCode['t'], 0, 4);

        baseByCode['N'][(encodedMatrixBytes[4] >> 6) & 3] = 'A';
        baseByCode['N'][(encodedMatrixBytes[4] >> 4) & 3] = 'C';
        baseByCode['N'][(encodedMatrixBytes[4] >> 2) & 3] = 'G';
        baseByCode['N'][(encodedMatrixBytes[4]) & 3] = 'T';

        for (final SubstitutionBase refBase : BASES) {
            for (byte code = 0; code < CODES_PER_BASE; code++) {
                codeByBase[refBase.getBase()][baseByCode[refBase.getBase()][code]] = code;
            }
        }
    }

    /**
     * Given a reference base and a read base, find the corresponding substitution code
     * @param refBase reference base being substituted
     * @param readBase read base to substitute for the reference base
     * @return code to be used for this refBase/readBase pair
     */
    public byte code(final byte refBase, final byte readBase) {
        if (refBase <= 0 || Character.isLowerCase((char) refBase)) {
            throw new IllegalArgumentException(
                    String.format("CRAM: Attempt to generate a substitution code for invalid or lower case reference base '%c'", (char) refBase));
        }
        if (readBase <= 0) {
            throw new IllegalArgumentException(
                    String.format("CRAM: Attempt to generate a substitution code for an invalid read base value '%c'", (char) readBase));
        }
        return codeByBase[refBase][readBase];
    }

    /**
     * Given a reference base and a substitution code, return the corresponding substitution base.
     * @param refBase reference base being substituted
     * @param code substitution code
     * @return base to be substituted for this (refBase, code) pair
     */
    public byte base(final byte refBase, final byte code) {
        if (refBase <= 0) {
            throw new IllegalArgumentException(
                    String.format("CRAM: Attempt to generate a substitution code for invalid reference base '%c'", (char) refBase));
        }
        final byte base = baseByCode[refBase][code];
        if (base == NO_BASE) {
            // attempt to retrieve a code for a reference base that isn't in the substitution matrix
            throw new IllegalArgumentException(String.format("CRAM: Attempt to retrieve a substitution base for invalid base '%c'", (char) refBase));
        }
        return base;
    }

    /**
     * Return this substitution matrix as a byte array in a form suitable for serialization.
     * @return the encoded matrix in serialized form
     */
    public byte[] getEncodedMatrix() {
        return encodedMatrixBytes;
    }

    @Override
    public String toString() {
        final int DISPLAY_SIZE = BASES_SIZE * (CODES_PER_BASE + 3) * 2;
        final StringBuilder stringBuilder = new StringBuilder(DISPLAY_SIZE);
        for (final SubstitutionBase r : BASES) {
            stringBuilder.append((char) r.getBase());
            stringBuilder.append(':');
            for (int i = 0; i < CODES_PER_BASE; i++) {
                stringBuilder.append((char) baseByCode[r.getBase()][i]);
            }
            stringBuilder.append('\t');
        }
        // lower case substitutions
        for (final SubstitutionBase r : BASES) {
            char lowerCaseBase = Character.toLowerCase((char)r.getBase());
            stringBuilder.append(lowerCaseBase);
            stringBuilder.append(':');
            for (int i = 0; i < CODES_PER_BASE; i++) {
                stringBuilder.append((char) baseByCode[lowerCaseBase][i]);
            }
            stringBuilder.append('\t');
        }
        return stringBuilder.toString();
    }

    // populate a matrix of substitution frequencies from a list of CramCompressionRecords with Substitution features
    private static long[][] buildFrequencies(final List<CRAMCompressionRecord> cramCompressionRecords) {
        final long[][] frequencies = new long[SYMBOL_SPACE_SIZE][SYMBOL_SPACE_SIZE];
        for (final CRAMCompressionRecord record : cramCompressionRecords) {
            if (record.getReadFeatures() != null) {
                for (final ReadFeature readFeature : record.getReadFeatures()) {
                    if (readFeature.getOperator() == Substitution.operator) {
                        final Substitution substitution = ((Substitution) readFeature);
                        final byte refBase = substitution.getReferenceBase();
                        final byte base = substitution.getBase();
                        if (refBase <= 0 || base <= 0) {
                            throw new IllegalArgumentException(
                                    String.format("CRAM: Attempt to generate a substitution code for invalid reference base with value '%d'", refBase));
                        }
                        frequencies[refBase][base]++;
                    }
                }
            }
        }
        return frequencies;
    }

    private static class SubstitutionFrequency {
        final SubstitutionBase substituteBase;
        long freq;
        byte rank;

        public SubstitutionFrequency(final SubstitutionBase substituteBase, final long freq) {
            this.substituteBase = substituteBase;
            this.freq = freq;
        }
    }

    private static final Comparator<SubstitutionFrequency> COMPARATOR =
            (o1, o2) -> {
                // primary sort by frequency
                if (o1.freq != o2.freq) {
                    return (int) (o2.freq - o1.freq);
                }
                // same frequency; compare based on spec tie-breaking rule (use base order prescribed by the spec)
                return o1.substituteBase.ordinal() - o2.substituteBase.ordinal();
            };

    // For the given base, return a packed substitution vector containing the possible
    // substitution codes given the set of substitution frequencies for that base.
    //
    // NOTE: this has a side effect in that is also populates the codeByBase matrix for this base.
    private byte substitutionCodeVector(final byte refBase, final long[] frequencies) {
        // there are 5 possible bases, so there are 4 possible substitutions for each base
        final SubstitutionFrequency[] subCodes = new SubstitutionFrequency[CODES_PER_BASE];
        int i = 0;
        for (final SubstitutionBase base : BASES) {
            if (refBase == base.getBase()) {
                continue;
            }
            subCodes[i++] = new SubstitutionFrequency(base, frequencies[base.getBase()]);
        }

        // sort the codes for this base based on substitution frequency
        Arrays.sort(subCodes, COMPARATOR);

        // set each SubstitutionFrequency to it's relative rank now that we know it, and reset the frequencies
        // so we can then re-sort, without frequency bias, back to the original (and prescribed)
        // order in which we want to emit the codes
        for (byte j = 0; j < subCodes.length; j++) {
            subCodes[j].rank = j;
        }

        for (final SubstitutionFrequency subCode1 : subCodes) {
            subCode1.freq = 0;
        }

        // re-sort back to the fixed order prescribed by the spec so we can store the substitution
        // codes in the matrix in the prescribed order
        Arrays.sort(subCodes, COMPARATOR);

        byte codeVector = 0;
        for (final SubstitutionFrequency subCode : subCodes) {
            codeVector <<= 2;
            codeVector |= subCode.rank;
        }

        for (final SubstitutionFrequency s : subCodes) {
            codeByBase[refBase][s.substituteBase.getBase()] = s.rank;
        }

        return codeVector;
    }

}
