package com.replymate.core.ports;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.util.Result;

/** Provider abstraction (decision #4). Implementations live in com.replymate.provider.
 *  Adding OpenAI/Claude later = new implementation + ProviderType value; core/app untouched. */
public interface AiProvider {
    /** Wire id matching ProviderType.wire (e.g. "gemini"). */
    String type();

    /** Run one generation call (implementation handles HTTP, retries, error mapping). */
    Result<ChatReply> generate(ChatRequest request);

    /** Cheap probe that the configured credentials work (1-token call). */
    Result<Boolean> validateKey();
}
