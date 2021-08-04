package net.yoplitein.badmap;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

public class RecordAdapterFactory implements TypeAdapterFactory
{
	@Override
	@SuppressWarnings("unchecked")
	public <R> TypeAdapter<R> create(Gson gson, TypeToken<R> type)
	{
		final Class<R> recordClass = (Class<R>)type.getRawType();
		if(!recordClass.isRecord()) return null;
		
		return new RecordAdapter<R>(gson, gson.getDelegateAdapter(this, type), recordClass);
	}
	
	static class RecordAdapter<R> extends TypeAdapter<R>
	{
		final Gson gson;
		final TypeAdapter<R> defaultWriter;
		final Class<R> recordClass;
		final Map<String, TypeToken<?>> fieldTypes;
		final RecordComponent[] fields;
		
		RecordAdapter(Gson gson, TypeAdapter<R> defaultWriter, Class<R> recordClass)
		{
			this.gson = gson;
			this.defaultWriter = defaultWriter;
			this.recordClass = recordClass;
			this.fields = recordClass.getRecordComponents();
			this.fieldTypes = Arrays
				.stream(fields)
				.collect(Collectors.toMap(t -> t.getName(), t -> TypeToken.get(t.getGenericType())))
			;
		}
		
		@Override
		public void write(JsonWriter out, R value) throws IOException
		{
			defaultWriter.write(out, value);
		}
		
		@Override
		public R read(JsonReader reader) throws IOException
		{
			if(reader.peek() == JsonToken.NULL)
			{
				reader.nextNull();
				return null;
			}
			
			final var argsMap = new HashMap<String, Object>();
			reader.beginObject();
			while(reader.hasNext())
			{
				final var name = reader.nextName();
				
				if(!fieldTypes.containsKey(name))
					throw new JsonParseException(
						String.format(
							"Attempted to deserialize record %s from JSON with key(s) that have no corresponding field (extraneous %s)",
							recordClass.getName(),
							name
						)
					);
				
				final var fieldType = fieldTypes.get(name);
				argsMap.put(name, gson.getAdapter(fieldType).read(reader));
			}
			reader.endObject();
			
			final var argTypes = new Class<?>[fields.length];
			final var values = new Object[fields.length];
			for(int index = 0; index < fields.length; index++)
			{
				final var field = fields[index];
				final var name = field.getName();
				
				// TODO: use a default value instead?
				if(!argsMap.containsKey(name))
					throw new JsonParseException(
						String.format(
							"Attempted to deserialize record %s from JSON with missing key(s) (missing %s)",
							recordClass.getName(),
							name
						)
					);
				
				argTypes[index] = field.getType();
				values[index] = argsMap.get(name);
			}
			
			try
			{
				final var constructor = recordClass.getDeclaredConstructor(argTypes);
				return constructor.newInstance(values);
			}
			catch(Exception err)
			{
				throw new RuntimeException("Failed to find/invoke record constructor", err);
			}
		}
	}
}
