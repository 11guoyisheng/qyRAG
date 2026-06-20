package com.example.rag.mapper;

import com.example.rag.model.ChatMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMemoryMapper {
    @Select("""
            SELECT id, session_id, memory_type, question, answer, content, created_at, updated_at
            FROM rag_chat_memory
            WHERE session_id = #{sessionId}
            ORDER BY created_at ASC, id ASC
            """)
    List<ChatMemory> findBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT COUNT(*)
            FROM rag_chat_memory
            WHERE session_id = #{sessionId}
            """)
    int countBySessionId(@Param("sessionId") String sessionId);

    @Insert("""
            INSERT INTO rag_chat_memory (session_id, memory_type, question, answer, content)
            VALUES (#{sessionId}, #{memoryType}, #{question}, #{answer}, #{content})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatMemory memory);

    @Delete("""
            DELETE FROM rag_chat_memory
            WHERE session_id = #{sessionId}
            """)
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
