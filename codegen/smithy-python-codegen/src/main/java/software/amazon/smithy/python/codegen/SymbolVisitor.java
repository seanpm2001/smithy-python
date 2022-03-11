/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.python.codegen;

import static java.lang.String.format;

import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.ReservedWordSymbolProvider;
import software.amazon.smithy.codegen.core.ReservedWords;
import software.amazon.smithy.codegen.core.ReservedWordsBuilder;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.SimpleShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.CaseUtils;
import software.amazon.smithy.utils.MediaType;
import software.amazon.smithy.utils.StringUtils;

/**
 * Responsible for type mapping and file/identifier formatting.
 *
 * <p>Reserved words for Python are automatically escaped so that they are
 * suffixed with "_". See "reserved-words.txt" for the list of words.
 */
abstract class SymbolVisitor implements SymbolProvider, ShapeVisitor<Symbol> {

    private static final Logger LOGGER = Logger.getLogger(SymbolVisitor.class.getName());

    protected final Model model;
    protected final ReservedWordSymbolProvider.Escaper escaper;
    protected final ReservedWordSymbolProvider.Escaper errorMemberEscaper;
    protected final PythonSettings settings;
    protected final ServiceShape service;

    SymbolVisitor(Model model, PythonSettings settings) {
        this.model = model;
        this.settings = settings;
        this.service = model.expectShape(settings.getService(), ServiceShape.class);

        // Load reserved words from a new-line delimited file.
        ReservedWords reservedWords = new ReservedWordsBuilder()
                .put("str", "str_")
                .build();

        escaper = ReservedWordSymbolProvider.builder()
                // TODO: escape reserved member names
                .nameReservedWords(reservedWords)
                // Only escape words when the symbol has a definition file to
                // prevent escaping intentional references to built-in types.
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();

        // Reserved words that only apply to error members.
        ReservedWords reservedErrorMembers = new ReservedWordsBuilder()
                .put("code", "code_")
                .build();

        errorMemberEscaper = ReservedWordSymbolProvider.builder()
                .memberReservedWords(reservedErrorMembers)
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();
    }

    @Override
    public final Symbol toSymbol(Shape shape) {
        Symbol symbol = shape.accept(this);
        LOGGER.fine(() -> format("Creating symbol from %s: %s", shape, symbol));
        return escaper.escapeSymbol(shape, symbol);
    }

    @Override
    public final String toMemberName(MemberShape shape) {
        if (CodegenUtils.isErrorMessage(model, shape))  {
            return "message";
        }

        var memberName = escaper.escapeMemberName(CaseUtils.toSnakeCase(shape.getMemberName()));

        // Escape words that are only reserved for error members.
        if (shape.hasTrait(ErrorTrait.class)) {
            memberName = errorMemberEscaper.escapeMemberName(memberName);
        }
        return memberName;
    }

    protected String getDefaultShapeName(Shape shape) {
        // Use the service-aliased name
        return StringUtils.capitalize(shape.getId().getName(service));
    }

    @Override
    public final Symbol blobShape(BlobShape shape) {
        if (shape.hasTrait(StreamingTrait.class)) {
            return createSymbolBuilder(shape, "StreamingBlob", "smithy_python.interfaces.blobs")
                    .addDependency(SmithyPythonDependency.SMITHY_PYTHON)
                    .build();
        }

        if (shape.hasTrait(MediaTypeTrait.class)) {
            var mediaType = shape.expectTrait(MediaTypeTrait.class).getValue();
            if (MediaType.isJson(mediaType)) {
                return createSymbolBuilder(shape, "Union[bytes, bytearray, JsonBlob]")
                        .addReference(createStdlibReference("Union", "typing"))
                        .addReference(Symbol.builder()
                                .name("JsonBlob")
                                .namespace("smithy_python.mediatypes", ".")
                                .addDependency(SmithyPythonDependency.SMITHY_PYTHON)
                                .build())
                        .build();
            }
        }
        return createSymbolBuilder(shape, "Union[bytes, bytearray]")
                .addReference(createStdlibReference("Union", "typing"))
                .build();
    }

    @Override
    public final Symbol booleanShape(BooleanShape shape) {
        return createSymbolBuilder(shape, "bool").build();
    }

    @Override
    public final Symbol listShape(ListShape shape) {
        return createCollectionSymbol(shape);
    }

    @Override
    public final Symbol setShape(SetShape shape) {
        // Python doesn't have a set type. Rather than hack together a set using a map,
        // we instead just create a list and let the service be responsible for
        // asserting that there are no duplicates.
        return createCollectionSymbol(shape);
    }

    private Symbol createCollectionSymbol(CollectionShape shape) {
        Symbol reference = toSymbol(shape.getMember());
        var builder = createSymbolBuilder(shape, "List[" + reference.getName() + "]")
                .addReference(createStdlibReference("List", "typing"))
                .addReference(reference);

        var target = model.expectShape(shape.getMember().getTarget());
        if (!(target instanceof SimpleShape)) {
            builder.putProperty("asDict", createAsDictFunctionSymbol(shape))
                    .putProperty("fromDict", createFromDictFunctionSymbol(shape));
        }
        return builder.build();
    }

    @Override
    public final Symbol mapShape(MapShape shape) {
        Symbol reference = toSymbol(shape.getValue());
        var builder = createSymbolBuilder(shape, "Dict[str, " + reference.getName() + "]")
                .addReference(createStdlibReference("Dict", "typing"))
                .addReference(reference);

        var target = model.expectShape(shape.getValue().getTarget());
        if (!(target instanceof SimpleShape)) {
            builder.putProperty("asDict", createAsDictFunctionSymbol(shape))
                    .putProperty("fromDict", createFromDictFunctionSymbol(shape));
        }
        return builder.build();
    }

    private Symbol createAsDictFunctionSymbol(Shape shape) {
        return Symbol.builder()
                .name(String.format("_%s_as_dict", CaseUtils.toSnakeCase(shape.getId().getName())))
                .namespace(format("%s.models", settings.getModuleName()), ".")
                .definitionFile(format("./%s/models.py", settings.getModuleName()))
                .build();
    }

    private Symbol createFromDictFunctionSymbol(Shape shape) {
        return Symbol.builder()
                .name(String.format("_%s_from_dict", CaseUtils.toSnakeCase(shape.getId().getName())))
                .namespace(format("%s.models", settings.getModuleName()), ".")
                .definitionFile(format("./%s/models.py", settings.getModuleName()))
                .build();
    }

    @Override
    public final Symbol byteShape(ByteShape shape) {
        return createSymbolBuilder(shape, "int").build();
    }

    @Override
    public final Symbol shortShape(ShortShape shape) {
        return createSymbolBuilder(shape, "int").build();
    }

    @Override
    public final Symbol integerShape(IntegerShape shape) {
        return createSymbolBuilder(shape, "int").build();
    }

    @Override
    public final Symbol longShape(LongShape shape) {
        return createSymbolBuilder(shape, "int").build();
    }

    @Override
    public final Symbol floatShape(FloatShape shape) {
        return createSymbolBuilder(shape, "float").build();
    }

    @Override
    public final Symbol documentShape(DocumentShape shape) {
        // TODO: implement document shapes
        return createStdlibSymbol(shape, "Any", "typing");
    }

    @Override
    public final Symbol doubleShape(DoubleShape shape) {
        return createSymbolBuilder(shape, "float").build();
    }

    @Override
    public final Symbol bigIntegerShape(BigIntegerShape shape) {
        return createSymbolBuilder(shape, "int").build();
    }

    @Override
    public final Symbol bigDecimalShape(BigDecimalShape shape) {
        return createStdlibSymbol(shape, "Decimal", "decimal");
    }

    @Override
    public Symbol resourceShape(ResourceShape shape) {
        // TODO: implement resources
        return createStdlibSymbol(shape, "Any", "typing");
    }

    @Override
    public final Symbol stringShape(StringShape shape) {
        var builder = createSymbolBuilder(shape, "str");
        if (shape.hasTrait(EnumTrait.class)) {
            String name = getDefaultShapeName(shape);
            Symbol enumSymbol = createSymbolBuilder(shape, name, format("%s.models", settings.getModuleName()))
                    .definitionFile(format("./%s/models.py", settings.getModuleName()))
                    .build();

            // We add this enum symbol as a property on a generic string symbol
            // rather than returning the enum symbol directly because we only
            // generate the enum constants for convenience. We actually want
            // to pass around plain strings rather than what is effectively
            // a namespace class.
            builder.putProperty("enumSymbol", escaper.escapeSymbol(shape, enumSymbol));
        }
        if (shape.hasTrait(MediaTypeTrait.class)) {
            var mediaType = shape.expectTrait(MediaTypeTrait.class).getValue();
            if (MediaType.isJson(mediaType)) {
                return createSymbolBuilder(shape, "Union[str, JsonString]")
                        .addReference(createStdlibReference("Union", "typing"))
                        .addReference(Symbol.builder()
                                .name("JsonString")
                                .namespace("smithy_python.mediatypes", ".")
                                .addDependency(SmithyPythonDependency.SMITHY_PYTHON)
                                .build())
                        .build();
            }
        }
        return builder.build();
    }

    @Override
    public final Symbol structureShape(StructureShape shape) {
        String name = getDefaultShapeName(shape);
        if (shape.hasTrait(ErrorTrait.class)) {
            return createSymbolBuilder(shape, name, format("%s.errors", settings.getModuleName()))
                    .definitionFile(format("./%s/errors.py", settings.getModuleName()))
                    .build();
        }
        return createSymbolBuilder(shape, name, format("%s.models", settings.getModuleName()))
                .definitionFile(format("./%s/models.py", settings.getModuleName()))
                .build();
    }

    @Override
    public final Symbol unionShape(UnionShape shape) {
        String name = getDefaultShapeName(shape);
        return createSymbolBuilder(shape, name, format("%s.models", settings.getModuleName()))
                .definitionFile(format("./%s/models.py", settings.getModuleName()))
                .putProperty("fromDict", createFromDictFunctionSymbol(shape))
                .build();
    }

    @Override
    public final Symbol memberShape(MemberShape shape) {
        Shape targetShape = model.getShape(shape.getTarget())
                .orElseThrow(() -> new CodegenException("Shape not found: " + shape.getTarget()));
        return toSymbol(targetShape);
    }

    @Override
    public final Symbol timestampShape(TimestampShape shape) {
        return createStdlibSymbol(shape, "datetime", "datetime");
    }

    protected Symbol.Builder createSymbolBuilder(Shape shape, String typeName) {
        return Symbol.builder().putProperty("shape", shape).name(typeName);
    }

    protected Symbol.Builder createSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    protected Symbol createStdlibSymbol(Shape shape, String typeName, String namespace) {
        return createSymbolBuilder(shape, typeName, namespace)
                .putProperty("stdlib", true)
                .build();
    }

    protected SymbolReference createStdlibReference(String typeName, String namespace) {
        return SymbolReference.builder()
                .symbol(createStdlibSymbol(null, typeName, namespace))
                .putProperty("stdlib", true)
                .options(SymbolReference.ContextOption.USE)
                .build();
    }
}
