# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(searchlib_util OBJECT
    SOURCES
    bufferwriter.cpp
    comprbuffer.cpp
    comprfile.cpp
    data_buffer_writer.cpp
    dirtraverse.cpp
    drainingbufferwriter.cpp
    field_index_stats.cpp
    file_with_header.cpp
    filealign.cpp
    fileheadertk.cpp
    filekit.cpp
    filesizecalculator.cpp
    fileutil.cpp
    foldedstringcompare.cpp
    linguisticsannotation.cpp
    logutil.cpp
    rawbuf.cpp
    searchable_stats.cpp
    slime_output_raw_buf_adapter.cpp
    state_explorer_utils.cpp
    token_extractor.cpp
    url.cpp
    DEPENDS
)
