/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *   http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.python.codegen.integration;

import software.amazon.smithy.aws.traits.protocols.RestJson1Trait;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.python.codegen.GenerationContext;
import software.amazon.smithy.python.codegen.HttpProtocolTestGenerator;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Abstract implementation of JSON-based protocols that use REST bindings.
 *
 * <p>This class will be capable of generating a functional protocol based on
 * the semantics of Amazon's RestJson1 protocol. Extension hooks will be
 * provided where necessary in the few cases where that protocol uses
 * Amazon-specific terminology or functionality.
 */
@SmithyUnstableApi
public class RestJsonProtocolGenerator extends HttpBindingProtocolGenerator {

    @Override
    public ShapeId getProtocol() {
        return RestJson1Trait.ID;
    }

    // This is here rather than in HttpBindingProtocolGenerator because eventually
    // it will need to generate some protocol-specific comparators.
    @Override
    public void generateProtocolTests(GenerationContext context) {
        context.writerDelegator().useFileWriter("./tests/test_protocol.py", "tests.test_protocol", writer -> {
            new HttpProtocolTestGenerator(context, getProtocol(), writer).run();
        });
    }
}