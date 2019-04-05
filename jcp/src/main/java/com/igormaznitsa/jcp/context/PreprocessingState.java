/*
 * Copyright 2002-2019 Igor Maznitsa (http://www.igormaznitsa.com)
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.igormaznitsa.jcp.context;


import com.igormaznitsa.jcp.containers.FileInfoContainer;
import com.igormaznitsa.jcp.containers.PreprocessingFlag;
import com.igormaznitsa.jcp.containers.TextFileDataContainer;
import com.igormaznitsa.jcp.exceptions.FilePositionInfo;
import com.igormaznitsa.jcp.exceptions.PreprocessorException;
import com.igormaznitsa.jcp.removers.JavaCommentsRemover;
import com.igormaznitsa.jcp.utils.PreprocessorUtils;
import com.igormaznitsa.jcp.utils.ResetablePrinter;
import com.igormaznitsa.meta.annotation.MustNotContainNull;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.igormaznitsa.meta.common.utils.Assertions.assertNotNull;
import static com.igormaznitsa.meta.common.utils.IOUtils.closeQuietly;

/**
 * The class describes a preprocessor state also it contains inside buffers and save data on disk
 *
 * @author Igor Maznitsa (igor.maznitsa@igormaznitsa.com)
 */
public final class PreprocessingState {

  public static final FilePositionInfo[] EMPTY_STACK = new FilePositionInfo[0];

  public static final int MAX_WRITE_BUFFER_SIZE = 65536;

  private final Charset globalInCharacterEncoding;
  private final Charset globalOutCharacterEncoding;
  private final TextFileDataContainer rootReference;
  private final FileInfoContainer rootFileInfo;
  private final LinkedList<TextFileDataContainer> whileStack = new LinkedList<>();
  private final LinkedList<TextFileDataContainer> ifStack = new LinkedList<>();
  private final LinkedList<TextFileDataContainer> includeStack = new LinkedList<>();
  private final LinkedList<ExcludeIfInfo> deferredExcludeStack = new LinkedList<>();
  private final ResetablePrinter prefixPrinter = new ResetablePrinter(1024);
  private final ResetablePrinter postfixPrinter = new ResetablePrinter(64 * 1024);
  private final ResetablePrinter normalPrinter = new ResetablePrinter(1024);
  private final boolean overrideOnlyIfContentChanged;
  private final EnumSet<PreprocessingFlag> preprocessingFlags = EnumSet.noneOf(PreprocessingFlag.class);
  private final PreprocessorContext context;
  private final boolean fake;
  private ResetablePrinter currentPrinter;
  private TextFileDataContainer activeIf;
  private TextFileDataContainer activeWhile;
  private String lastReadString;

  PreprocessingState(@Nonnull final PreprocessorContext context, @Nonnull final Charset inEncoding, @Nonnull final Charset outEncoding) {
    this.fake = true;
    this.globalInCharacterEncoding = assertNotNull(inEncoding);
    this.globalOutCharacterEncoding = assertNotNull(outEncoding);
    this.rootReference = null;
    this.lastReadString = "";
    this.rootFileInfo = new FileInfoContainer(new File("global"), "global", true);
    this.overrideOnlyIfContentChanged = true;
    this.context = context;
    init();
  }

  PreprocessingState(@Nonnull final PreprocessorContext context, @Nonnull final FileInfoContainer rootFile, @Nonnull final Charset inEncoding, @Nonnull final Charset outEncoding, final boolean overrideOnlyIfContentChanged) throws IOException {
    this.fake = false;

    this.context = context;

    this.overrideOnlyIfContentChanged = overrideOnlyIfContentChanged;
    this.globalInCharacterEncoding = assertNotNull(inEncoding);
    this.globalOutCharacterEncoding = assertNotNull(outEncoding);

    this.rootFileInfo = assertNotNull("The root file is null", rootFile);
    init();
    rootReference = openFile(rootFile.getSourceFile());
  }

  PreprocessingState(@Nonnull final PreprocessorContext context, @Nonnull final FileInfoContainer rootFile, @Nonnull final TextFileDataContainer rootContainer, @Nonnull final Charset inEncoding, @Nonnull final Charset outEncoding, final boolean overrideOnlyIfContentChanged) {
    this.fake = false;

    this.context = context;

    this.globalInCharacterEncoding = assertNotNull(inEncoding);
    this.globalOutCharacterEncoding = assertNotNull(outEncoding);
    this.overrideOnlyIfContentChanged = overrideOnlyIfContentChanged;

    this.rootFileInfo = assertNotNull("The root file is null", rootFile);
    init();
    rootReference = rootContainer;
    includeStack.push(rootContainer);
  }

  @Nonnull
  private static Charset decodeCharset(@Nonnull final String name) {
    try {
      return Charset.forName(name);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Can't decode charset name: " + name);
    }
  }

  @Nullable
  public String getLastReadString() {
    return this.lastReadString;
  }

  public void pushExcludeIfData(@Nonnull final FileInfoContainer infoContainer, @Nonnull final String excludeIfCondition, final int stringIndex) {
    assertNotNull("File info is null", infoContainer);
    assertNotNull("Condition is null", excludeIfCondition);

    if (stringIndex < 0) {
      throw new IllegalArgumentException("Unexpected string index [" + stringIndex + ']');
    }

    deferredExcludeStack.push(new ExcludeIfInfo(infoContainer, excludeIfCondition, stringIndex));
  }

  @Nonnull
  @MustNotContainNull
  public List<ExcludeIfInfo> popAllExcludeIfInfoData() {
    final List<ExcludeIfInfo> result = new ArrayList<>(deferredExcludeStack);
    deferredExcludeStack.clear();
    return result;
  }

  @Nonnull
  public ExcludeIfInfo popExcludeIfData() {
    return deferredExcludeStack.pop();
  }

  @Nonnull
  public Set<PreprocessingFlag> getPreprocessingFlags() {
    return preprocessingFlags;
  }

  @Nullable
  public ResetablePrinter getPrinter() throws IOException {
    return currentPrinter;
  }

  public void setPrinter(@Nonnull final PrinterType type) {
    assertNotNull("Type is null", type);
    switch (type) {
      case NORMAL:
        currentPrinter = normalPrinter;
        break;
      case POSTFIX:
        currentPrinter = postfixPrinter;
        break;
      case PREFIX:
        currentPrinter = prefixPrinter;
        break;
      default:
        throw new IllegalArgumentException("Unsupported type detected [" + type.name() + ']');
    }
  }

  @Nonnull
  public TextFileDataContainer getRootTextContainer() {
    return rootReference;
  }

  @Nonnull
  public TextFileDataContainer openFile(@Nonnull final File file) throws IOException {
    assertNotNull("The file is null", file);

    final AtomicBoolean endedByNextLineContainer = new AtomicBoolean();

    final String[] texts = PreprocessorUtils.readWholeTextFileIntoArray(file, globalInCharacterEncoding, endedByNextLineContainer);
    final TextFileDataContainer newContainer = new TextFileDataContainer(file, texts, endedByNextLineContainer.get(), 0);
    includeStack.push(newContainer);
    return newContainer;
  }

  @Nullable
  public TextFileDataContainer peekFile() {
    return includeStack.peek();
  }

  @Nonnull
  @MustNotContainNull
  List<TextFileDataContainer> getCurrentIncludeStack() {
    return this.includeStack;
  }

  @Nonnull
  @MustNotContainNull
  public FilePositionInfo[] makeIncludeStack() {
    if (this.fake) {
      return EMPTY_STACK;
    }

    final FilePositionInfo[] stack = new FilePositionInfo[includeStack.size()];
    for (int i = 0; i < includeStack.size(); i++) {
      final TextFileDataContainer fileContainer = includeStack.get(i);
      stack[i] = new FilePositionInfo(fileContainer.getFile(), fileContainer.getLastReadStringIndex());
    }
    return stack;
  }

  @Nullable
  public TextFileDataContainer getCurrentIncludeFileContainer() {
    return this.includeStack.isEmpty() ? null : this.includeStack.get(this.includeStack.size() - 1);
  }

  @Nonnull
  public TextFileDataContainer popTextContainer() {
    if (includeStack.isEmpty()) {
      throw new IllegalStateException("Include stack is empty");
    }
    return includeStack.pop();
  }

  @Nonnull
  public FileInfoContainer getRootFileInfo() {
    return rootFileInfo;
  }

  public boolean isIncludeStackEmpty() {
    return includeStack.isEmpty();
  }

  public boolean isOnlyRootOnStack() {
    return includeStack.size() == 1;
  }

  @Nonnull
  private TextFileDataContainer cloneTopTextDataContainer(final boolean useLastReadStringIndex) {
    final TextFileDataContainer topElement = includeStack.peek();
    return new TextFileDataContainer(topElement, useLastReadStringIndex ? topElement.getLastReadStringIndex() : topElement.getNextStringIndex());
  }

  @Nonnull
  public PreprocessingState popWhile() {
    final TextFileDataContainer whileOnTop = whileStack.pop();
    if (whileOnTop == activeWhile) {
      preprocessingFlags.remove(PreprocessingFlag.BREAK_COMMAND);
      if (whileStack.isEmpty()) {
        activeWhile = null;
      } else {
        activeWhile = whileStack.peek();
      }
    }
    return this;
  }

  @Nonnull
  public PreprocessingState pushWhile(final boolean makeActive) {
    final TextFileDataContainer whileRef = cloneTopTextDataContainer(true);
    whileStack.push(whileRef);
    if (makeActive) {
      activeWhile = whileRef;
    }
    return this;
  }

  @Nullable
  public TextFileDataContainer peekWhile() {
    return whileStack.peek();
  }

  public boolean hasReadLineNextLineInEnd() {
    return includeStack.peek().isPresentedNextLineOnReadString();
  }

  @Nullable
  public String nextLine() {
    final String result = includeStack.peek().nextLine();
    this.lastReadString = result;
    return result;
  }

  @Nonnull
  public PreprocessingState goToString(final int stringIndex) {
    includeStack.peek().setNextStringIndex(stringIndex);
    return this;
  }

  @Nonnull
  public PreprocessingState pushIf(final boolean makeActive) {
    final TextFileDataContainer ifRef = cloneTopTextDataContainer(true);
    ifStack.push(ifRef);
    if (makeActive) {
      activeIf = ifRef;
    }
    return this;
  }

  public void popAllIFUntilContainerWithFile(@Nonnull final TextFileDataContainer container) {
    final File file = container.getFile();
    final int stringIndex = container.getNextStringIndex();
    while (!ifStack.isEmpty()) {
      final TextFileDataContainer top = ifStack.peek();
      if (!top.getFile().equals(file) || top.getNextStringIndex() <= stringIndex) {
        break;
      } else {
        ifStack.pop();
      }
    }
  }

  @Nonnull
  public PreprocessingState popIf() {
    final TextFileDataContainer ifRef = ifStack.pop();
    if (ifRef == activeIf) {
      if (ifStack.isEmpty()) {
        activeIf = null;
      } else {
        activeIf = ifStack.peek();
      }
    }
    return this;
  }

  public boolean isAtActiveWhile() {
    if (whileStack.isEmpty()) {
      return true;
    } else {
      return activeWhile == whileStack.peek();
    }
  }

  public boolean isAtActiveIf() {
    if (ifStack.isEmpty()) {
      return true;
    } else {
      return ifStack.peek() == activeIf;
    }
  }

  public boolean isDirectiveCanBeProcessedIgnoreBreak() {
    return isAtActiveIf() && isAtActiveWhile() && !preprocessingFlags.contains(PreprocessingFlag.IF_CONDITION_FALSE);
  }

  public boolean isDirectiveCanBeProcessed() {
    return isDirectiveCanBeProcessedIgnoreBreak() && !preprocessingFlags.contains(PreprocessingFlag.BREAK_COMMAND);
  }

  @Nullable
  public TextFileDataContainer peekIf() {
    return ifStack.peek();
  }

  public boolean isIfStackEmpty() {
    return ifStack.isEmpty();
  }

  public boolean isWhileStackEmpty() {
    return whileStack.isEmpty();
  }

  private void init() {
    preprocessingFlags.clear();
    resetPrinters();

    setPrinter(PrinterType.NORMAL);
  }

  public void resetPrinters() {
    normalPrinter.reset();
    prefixPrinter.reset();
    postfixPrinter.reset();

    currentPrinter = normalPrinter;
  }

  public void saveBuffersToStreams(@Nonnull final OutputStream prefix, @Nonnull final OutputStream normal, @Nonnull final OutputStream postfix) throws IOException {
    prefixPrinter.writeBufferTo(new BufferedWriter(new OutputStreamWriter(prefix, globalOutCharacterEncoding)));
    normalPrinter.writeBufferTo(new BufferedWriter(new OutputStreamWriter(normal, globalOutCharacterEncoding)));
    postfixPrinter.writeBufferTo(new BufferedWriter(new OutputStreamWriter(postfix, globalOutCharacterEncoding)));
  }

  public boolean saveBuffersToFile(@Nonnull final File outFile, final boolean keepComments) throws IOException {
    final File path = outFile.getParentFile();

    if (path != null && !path.exists() && !path.mkdirs()) {
      throw new IOException("Can't make directory [" + PreprocessorUtils.getFilePath(path) + ']');
    }

    Writer writer = null;

    boolean wasSaved = false;
    try {
      final int totatBufferedChars = prefixPrinter.getSize() + normalPrinter.getSize() + postfixPrinter.getSize();
      final int BUFFER_SIZE = Math.max(64, Math.min(totatBufferedChars << 1, MAX_WRITE_BUFFER_SIZE));

      if (this.overrideOnlyIfContentChanged) {
        String content = writePrinterBuffers(new StringWriter(totatBufferedChars)).toString();
        if (!keepComments) {
          content = new JavaCommentsRemover(new StringReader(content), new StringWriter(totatBufferedChars)).process().toString();
        }

        boolean needWrite = true; // better write than not
        final byte[] contentInBinaryForm = content.getBytes(globalOutCharacterEncoding);
        if (outFile.isFile() && outFile.length() == contentInBinaryForm.length) {
          // If file exists and has the same content, then skip overwriting it
          try (InputStream currentFileInputStream = new BufferedInputStream(new FileInputStream(outFile), Math.max(16384, (int) outFile.length()))) {
            needWrite = !IOUtils.contentEquals(currentFileInputStream, new ByteArrayInputStream(contentInBinaryForm));
          }
        }
        if (needWrite) {
          FileUtils.writeByteArrayToFile(outFile, contentInBinaryForm, false);
          wasSaved = true;
        } else {
          this.context.logDebug("Ignore writing data for " + outFile + " because its content has not been changed");
        }
      } else if (!keepComments) {
        final String joinedBufferContent = writePrinterBuffers(new StringWriter(totatBufferedChars)).toString();
        writer = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(outFile, false), BUFFER_SIZE), globalOutCharacterEncoding);
        new JavaCommentsRemover(new StringReader(joinedBufferContent), writer).process();
        wasSaved = true;
      } else {
        writer = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(outFile, false), BUFFER_SIZE), globalOutCharacterEncoding);
        writePrinterBuffers(writer);
        wasSaved = true;
      }

    } finally {
      closeQuietly(writer);
    }

    if (wasSaved && this.context.isKeepAttributes() && outFile.exists()) {
      PreprocessorUtils.copyFileAttributes(this.getRootFileInfo().getSourceFile(), outFile);
    }

    return wasSaved;
  }

  @Nonnull
  public Writer writePrinterBuffers(@Nonnull final Writer writer) throws IOException {
    if (!prefixPrinter.isEmpty()) {
      prefixPrinter.writeBufferTo(writer);
    }

    if (!normalPrinter.isEmpty()) {
      normalPrinter.writeBufferTo(writer);
    }

    if (!postfixPrinter.isEmpty()) {
      postfixPrinter.writeBufferTo(writer);
    }

    return writer;
  }

  @Nonnull
  public PreprocessorException makeException(@Nullable final String message, @Nullable final String causeString, @Nullable final Throwable cause) {
    return new PreprocessorException(message, causeString, makeIncludeStack(), cause);
  }

  public void dispose() {
    this.deferredExcludeStack.clear();
    this.ifStack.clear();
    this.includeStack.clear();
    this.whileStack.clear();
  }

  public enum PrinterType {

    NORMAL,
    PREFIX,
    POSTFIX
  }

  public static class ExcludeIfInfo {

    private final FileInfoContainer fileInfoContainer;
    private final String condition;
    private final int stringIndex;

    public ExcludeIfInfo(@Nonnull final FileInfoContainer fileInfoContainer, @Nonnull final String condition, final int stringIndex) {
      this.fileInfoContainer = fileInfoContainer;
      this.condition = condition.trim();
      this.stringIndex = stringIndex;
    }

    public int getStringIndex() {
      return this.stringIndex;
    }

    @Nonnull
    public FileInfoContainer getFileInfoContainer() {
      return fileInfoContainer;
    }

    @Nonnull
    public String getCondition() {
      return condition;
    }
  }
}
