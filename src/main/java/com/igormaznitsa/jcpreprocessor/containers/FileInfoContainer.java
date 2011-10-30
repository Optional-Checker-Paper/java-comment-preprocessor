package com.igormaznitsa.jcpreprocessor.containers;

import com.igormaznitsa.jcpreprocessor.context.JCPSpecialVariables;
import com.igormaznitsa.jcpreprocessor.context.PreprocessorContext;
import com.igormaznitsa.jcpreprocessor.directives.AbstractDirectiveHandler;
import com.igormaznitsa.jcpreprocessor.directives.AfterProcessingBehaviour;
import com.igormaznitsa.jcpreprocessor.exceptions.PreprocessorException;
import com.igormaznitsa.jcpreprocessor.expression.Value;
import com.igormaznitsa.jcpreprocessor.removers.JavaCommentsRemover;
import com.igormaznitsa.jcpreprocessor.utils.PreprocessorUtils;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;

public class FileInfoContainer {

    private final File sourceFile;
    private final boolean forCopyOnly;
    private boolean excludedFromPreprocessing;
    private String destinationDir;
    private String destinationName;

    public File getSourceFile() {
        return sourceFile;
    }

    public boolean isExcludedFromPreprocessing() {
        return excludedFromPreprocessing;
    }

    public boolean isForCopyOnly() {
        return forCopyOnly;
    }

    public String getDestinationDir() {
        return destinationDir;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public FileInfoContainer(final File srcFile, final String dstFileName, final boolean copingOnly) {
        forCopyOnly = copingOnly;
        excludedFromPreprocessing = false;
        sourceFile = srcFile;

        int dirSeparator = dstFileName.lastIndexOf('/');
        if (dirSeparator < 0) {
            dirSeparator = dstFileName.lastIndexOf('\\');
        }

        if (dirSeparator < 0) {
            destinationDir = "." + File.separatorChar;
            destinationName = dstFileName;
        } else {
            destinationDir = dstFileName.substring(0, dirSeparator);
            destinationName = dstFileName.substring(dirSeparator);
        }
    }

    public String getDestinationFilePath() {
        return destinationDir + File.separatorChar + destinationName;
    }

    @Override
    public String toString() {
        return sourceFile.getAbsolutePath();
    }

    private void printSpaces(final PreprocessingState paramContainer, final int number) throws IOException {
        for (int li = 0; li < number; li++) {
            paramContainer.getPrinter().print(" ");
        }
    }

    public List<PreprocessingState.ExcludeIfInfo> processGlobalDirectives(final PreprocessorContext context) throws PreprocessorException, IOException {
        final PreprocessingState paramContainer = new PreprocessingState(this, context.getCharacterEncoding());

        String trimmedProcessingString = null;
        try {
            while (true) {
                String nonTrimmedProcessingString = paramContainer.nextLine();
                if (paramContainer.getPreprocessingFlags().contains(PreprocessingFlag.END_PROCESSING)) {
                    nonTrimmedProcessingString = null;
                }

                if (nonTrimmedProcessingString == null) {
                    if (!paramContainer.isOnlyRootOnStack()) {
                        paramContainer.popTextContainer();
                        continue;
                    } else {
                        break;
                    }
                }

                trimmedProcessingString = nonTrimmedProcessingString.trim();

                final int numberOfSpacesAtTheLineBeginning = nonTrimmedProcessingString.indexOf(trimmedProcessingString);

                if (trimmedProcessingString.startsWith(AbstractDirectiveHandler.DIRECTIVE_PREFIX)) {
                    switch (processDirective(paramContainer, PreprocessorUtils.extractTail(AbstractDirectiveHandler.DIRECTIVE_PREFIX, trimmedProcessingString), context,true)) {
                        case PROCESSED:
                        case READ_NEXT_LINE:
                            continue;
                        default:
                            throw new Error("Unsupported result");
                    }
                }
            }
        } catch (Exception e) {
            throw new PreprocessorException("Exception during preprocessing [" + e.getMessage() + "][" + paramContainer.getFileIncludeStackAsString() + ']',
                    paramContainer.getRootFileInfo().getSourceFile(),
                    paramContainer.peekFile().getFile(),
                    trimmedProcessingString,
                    paramContainer.peekFile().getNextStringIndex(), e);
        }

        if (!paramContainer.isIfStackEmpty()) {
            throw new PreprocessorException("Unclosed " + AbstractDirectiveHandler.DIRECTIVE_PREFIX + "_if instruction",
                    paramContainer.getRootFileInfo().getSourceFile(),
                    paramContainer.peekIf().getFile(), null, paramContainer.peekIf().getNextStringIndex() + 1, null);
        }
        
        return paramContainer.popAllExcludeIfInfoData();
    }
    
    public PreprocessingState preprocessFile(final PreprocessingState state, final PreprocessorContext configurator) throws IOException, PreprocessorException {
        configurator.clearLocalVariables();
        final PreprocessingState preprocessingState = state != null ? state : new PreprocessingState(this, configurator.getCharacterEncoding());
        
        String trimmedProcessingString = null;
        try {
            while (true) {
                String nonTrimmedProcessingString = preprocessingState.nextLine();
                if (preprocessingState.getPreprocessingFlags().contains(PreprocessingFlag.END_PROCESSING)) {
                    nonTrimmedProcessingString = null;
                }

                if (nonTrimmedProcessingString == null) {
                    if (!preprocessingState.isOnlyRootOnStack()) {
                        preprocessingState.popTextContainer();
                        continue;
                    } else {
                        break;
                    }
                }

                trimmedProcessingString = nonTrimmedProcessingString.trim();

                final int numberOfSpacesAtTheLineBeginning = nonTrimmedProcessingString.indexOf(trimmedProcessingString);

                String stringToBeProcessed = trimmedProcessingString;
                if (!trimmedProcessingString.startsWith("//$$")) {
                    stringToBeProcessed = PreprocessorUtils.processMacroses(trimmedProcessingString, configurator,preprocessingState);
                }

                if (stringToBeProcessed.startsWith(AbstractDirectiveHandler.DIRECTIVE_PREFIX)) {
                    switch (processDirective(preprocessingState, PreprocessorUtils.extractTail(AbstractDirectiveHandler.DIRECTIVE_PREFIX, stringToBeProcessed), configurator,false)) {
                        case PROCESSED:
                        case READ_NEXT_LINE:
                            continue;
                        default:
                            throw new Error("Unsupported result");
                    }
                }

                if (preprocessingState.isDirectiveCanBeProcessed() && !preprocessingState.getPreprocessingFlags().contains(PreprocessingFlag.TEXT_OUTPUT_DISABLED)) {
                    if (stringToBeProcessed.startsWith("//$$")) {
                        // Output the tail of the string to the output stream without comments and macroses
                        printSpaces(preprocessingState, numberOfSpacesAtTheLineBeginning);
                        preprocessingState.getPrinter().println(PreprocessorUtils.extractTail("//$$", trimmedProcessingString));
                    } else if (stringToBeProcessed.startsWith("//$")) {
                        // Output the tail of the string to the output stream without comments
                        printSpaces(preprocessingState, numberOfSpacesAtTheLineBeginning);
                        preprocessingState.getPrinter().println(PreprocessorUtils.extractTail("//$", stringToBeProcessed));
                    } else {
                        // Just string :)
                        final String strToOut = processStringForTailRemover(stringToBeProcessed);

                        if (preprocessingState.getPreprocessingFlags().contains(PreprocessingFlag.COMMENT_NEXT_LINE)) {
                            preprocessingState.getPrinter().print("//");
                            preprocessingState.getPreprocessingFlags().remove(PreprocessingFlag.COMMENT_NEXT_LINE);
                        }

                        printSpaces(preprocessingState, numberOfSpacesAtTheLineBeginning);
                        preprocessingState.getPrinter().println(stringToBeProcessed);
                    }
                }

            }
        } catch (Exception possibleExceptionDuringExpressionEval) {
            throw new PreprocessorException("Exception during preprocessing [" + possibleExceptionDuringExpressionEval.getMessage() + "][" + preprocessingState.getFileIncludeStackAsString() + ']',
                    preprocessingState.getRootFileInfo().getSourceFile(),
                    preprocessingState.peekFile().getFile(),
                    trimmedProcessingString,
                    preprocessingState.peekFile().getNextStringIndex(), possibleExceptionDuringExpressionEval);
        }

        if (!preprocessingState.isIfStackEmpty()) {
            throw new PreprocessorException("Unclosed " + AbstractDirectiveHandler.DIRECTIVE_PREFIX + "if instruction",
                    preprocessingState.getRootFileInfo().getSourceFile(),
                    preprocessingState.peekIf().getFile(), null, preprocessingState.peekIf().getNextStringIndex() + 1, null);
        }
        if (!preprocessingState.isWhileStackEmpty()) {
            throw new PreprocessorException("Unclosed " + AbstractDirectiveHandler.DIRECTIVE_PREFIX + "when instruction",
                    preprocessingState.getRootFileInfo().getSourceFile(),
                    preprocessingState.peekWhile().getFile(), null, preprocessingState.peekWhile().getNextStringIndex() + 1, null);
        }

        final File outFile = configurator.makeDestinationFile(getDestinationFilePath());
        preprocessingState.saveBuffersToFile(outFile);
        
        return preprocessingState;
    }

    private static String processStringForTailRemover(final String str) {
        final int tailRemoverStart = str.indexOf("/*-*/");
        if (tailRemoverStart >= 0) {
            return str.substring(0, tailRemoverStart);
        }
        return str;
    }

    protected AfterProcessingBehaviour processDirective(final PreprocessingState state, final String trimmedString, final PreprocessorContext configurator, final boolean firstPass) throws IOException {
        final boolean executionEnabled = state.isDirectiveCanBeProcessed();

        for (final AbstractDirectiveHandler handler : AbstractDirectiveHandler.DIRECTIVES) {
            final String name = handler.getName();
            if (trimmedString.startsWith(name)) {
                if ((firstPass && !handler.isGlobalPhaseAllowed()) || (!firstPass && !handler.isPreprocessingPhaseAllowed())) {
                    return AfterProcessingBehaviour.READ_NEXT_LINE;
                }
                
                final boolean allowedForExecution = executionEnabled || !handler.executeOnlyWhenExecutionAllowed();

                final String restOfString = PreprocessorUtils.extractTail(name, trimmedString);
                if (handler.hasExpression()) {
                    if (!restOfString.isEmpty() && Character.isSpaceChar(restOfString.charAt(0))) {
                        if (allowedForExecution) {
                            return handler.execute(restOfString.trim(), state, configurator);
                        } else {
                            return AfterProcessingBehaviour.PROCESSED;
                        }
                    } else {
                        if (allowedForExecution) {
                            throw new RuntimeException("Directive " + AbstractDirectiveHandler.DIRECTIVE_PREFIX + handler.getName() + " needs an expression");
                        } else {
                            return AfterProcessingBehaviour.PROCESSED;
                        }
                    }
                } else {
                    if (allowedForExecution) {
                        return handler.execute(restOfString, state, configurator);
                    } else {
                        return AfterProcessingBehaviour.PROCESSED;
                    }
                }
            }
        }
        throw new RuntimeException("Unknown preprocessor directive [" + trimmedString + ']');
    }

    private final void removeCommentsFromFile(final File file, final PreprocessorContext cfg) throws IOException {
        int len = (int) file.length();
        int pos = 0;
        final byte[] memoryFile = new byte[len];
        FileInputStream inStream = new FileInputStream(file);
        try {
            while (len > 0) {
                final int read = inStream.read(memoryFile, pos, len);
                if (read < 0) {
                    break;
                }
                pos += read;
                len -= read;
            }

            if (len > 0) {
                throw new IOException("Wrong read length");
            }
        } finally {
            try {
                inStream.close();
            } catch (IOException ex) {
            }
        }

        if (!file.delete()) {
            throw new IOException("Can't delete the file " + file.getAbsolutePath());
        }

        final Reader reader = new InputStreamReader(new ByteArrayInputStream(memoryFile), cfg.getCharacterEncoding());

        final FileWriter writer = new FileWriter(file, false);
        try {
            new JavaCommentsRemover(reader, writer).process();
            writer.flush();
        } finally {
            try {
                writer.close();
            } catch (IOException ex) {
            }
        }
    }

    public void setDestinationDir(final String stringToBeProcessed) {
        destinationDir = stringToBeProcessed;
    }

    public void setDestinationName(String stringToBeProcessed) {
        destinationName = stringToBeProcessed;
    }

    public void setExcluded(final boolean flag) {
        excludedFromPreprocessing = flag;
    }
}
