package com.perceivedev.perceivecore.config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.util.Vector;

import com.perceivedev.perceivecore.config.handlers.LocationSerializer;
import com.perceivedev.perceivecore.config.handlers.VectorSerializer;
import com.perceivedev.perceivecore.config.handlers.WorldSerializer;

/**
 * Manages the serialization
 */
public class SerializationManager {

    private static final int	       MAX_DEPTH	      = 20;
    private static final Set<Class<?>> RAW_INSERTABLE_CLASSES = new HashSet<>();

    static {
	RAW_INSERTABLE_CLASSES.add(String.class);

	RAW_INSERTABLE_CLASSES.add(Number.class);
	RAW_INSERTABLE_CLASSES.add(Long.class);
	RAW_INSERTABLE_CLASSES.add(Integer.class);
	RAW_INSERTABLE_CLASSES.add(Double.class);
	RAW_INSERTABLE_CLASSES.add(Float.class);
	RAW_INSERTABLE_CLASSES.add(Short.class);
	RAW_INSERTABLE_CLASSES.add(Byte.class);
    }

    private static Map<Class<?>, SerializationProxy<?>> serializationProxyMap = new HashMap<>();

    static {
	serializationProxyMap.put(Vector.class, new VectorSerializer());
	serializationProxyMap.put(Location.class, new LocationSerializer());
	serializationProxyMap.put(World.class, new WorldSerializer());
    }

    /**
     * Adds a proxy for a class
     *
     * @param clazz
     *            The clazz to proxy
     * @param proxy
     *            The proxy
     * @param <T>
     *            The Type of the clazz to proxy
     */
    public <T> void addSerializationProxy(Class<T> clazz, SerializationProxy<T> proxy) {
	serializationProxyMap.put(clazz, proxy);
    }

    /**
     * Serializes a class
     *
     * @param configSerializable
     *            The {@link ConfigSerializable} to serialize
     *
     * @return The Serialized form
     *
     * @throws IllegalArgumentException
     *             if a field couldn't be serialized
     * @throws IllegalStateException
     *             if a too deep loop is detected
     */
    public Map<String, Object> serialize(ConfigSerializable configSerializable) {
	return serialize(configSerializable, 0);
    }

    /**
     * Serializes a class
     *
     * @param configSerializable
     *            The {@link ConfigSerializable} to serialize
     * @param depth
     *            The recursion depth
     *
     * @return The Serialized form
     *
     * @throws IllegalArgumentException
     *             if a field couldn't be serialized
     * @throws IllegalStateException
     *             if a too deep loop is detected
     */
    private Map<String, Object> serialize(ConfigSerializable configSerializable, int depth) {
	if (configSerializable == null) {
	    return Collections.emptyMap();
	}
	if (depth > MAX_DEPTH) {
	    throw new IllegalStateException("Trapped in a loop? Recursion amount too high.");
	}

	Map<String, Object> map = new HashMap<>();

	for (Field field : getFieldsToSerialize(configSerializable.getClass())) {
	    Class<?> type = field.getType();

	    if (getField(field, configSerializable) == null) {
		map.put(field.getName(), null);
		continue;
	    }

	    if (ConfigSerializable.class.isAssignableFrom(type)) {
		Map<String, Object> nestedSerialized = serialize(getField(field, configSerializable), depth + 1);
		map.put(field.getName(), nestedSerialized);
	    } else if (serializationProxyMap.containsKey(type)) {
		SerializationProxy<?> proxy = serializationProxyMap.get(type);
		Map<String, Object> serialized = proxy.serialize(getField(field, configSerializable));
		map.put(field.getName(), serialized);
	    } else if (ConfigurationSerializable.class.isAssignableFrom(type)) {
		ConfigurationSerializable configurationSerializable = getField(field, configSerializable);

		// will actually be the case as I check in the
		// first statement. Just to shut IntelliJ up
		// and don't add any SuppressWarnings
		// eclipse has never heard of.
		assert configurationSerializable != null;

		map.put(field.getName(), configurationSerializable.serialize());
	    } else if (RAW_INSERTABLE_CLASSES.contains(type)) {
		map.put(field.getName(), getField(field, configSerializable));
	    } else {
		throw new IllegalArgumentException("The field " + field.getName() + " of type " + type.getName() + " is  not serializable");
	    }
	}

	return map;
    }

    /**
     * Deserializes an object.
     *
     * @param clazz
     *            The clazz to deserialize
     * @param data
     *            The serialized data
     * @param <T>
     *            The type of the class to deserialize
     *
     * @return The deserialized class
     */
    public <T> T deserialize(Class<T> clazz, Map<String, Object> data) {
	return deserialize(clazz, data, 0);
    }

    /**
     * Deserializes an object.
     *
     * @param clazz
     *            The clazz to deserialize
     * @param data
     *            The serialized data
     * @param depth
     *            The recursion depth
     * @param <T>
     *            The type of the class to deserialize
     *
     * @return The deserialized class
     *
     * @throws IllegalStateException
     *             if a too deep loop is detected
     * @throws IllegalArgumentException
     *             if it doesn't know how to deal with a field
     */
    private <T> T deserialize(Class<T> clazz, Map<String, Object> data, int depth) {
	if (depth > MAX_DEPTH) {
	    throw new IllegalStateException("Trapped in a loop? Recursion amount too high.");
	}

	T instance = instantiate(clazz);
	if (instance == null) {
	    return null;
	}

	for (Field field : getFieldsToSerialize(clazz)) {
	    Class<?> type = field.getType();

	    if (data.containsKey(field.getName())) {
		Object serializedData = data.get(field.getName());

		// don't let the deserializers deal with nulls.
		// Do it yourself.
		if (serializedData == null) {
		    setField(field, instance, null);
		    continue;
		}

		if (ConfigSerializable.class.isAssignableFrom(type)) {

		    if (serializedData instanceof Map) {
			// is assumed. If this false,
			// the data has been modified.
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) serializedData;

			setField(field, instance, deserialize(type, map, depth + 1));
		    }
		} else if (serializationProxyMap.containsKey(type)) {
		    SerializationProxy<?> serializationProxy = serializationProxyMap.get(type);

		    // is actually checked by the contains
		    // call
		    @SuppressWarnings("unchecked")
		    Map<String, Object> map = (Map<String, Object>) serializedData;

		    setField(field, instance, serializationProxy.deserialize(map));
		} else if (ConfigurationSerializable.class.isAssignableFrom(type)) {
		    if (serializedData instanceof Map) {
			System.out.println("Found a map. This shouldn't happen, as it means the Bukkit configuration hasn't done it's job.");
			// TODO: @I_Al_Istannen: This is
			// bad. We have NO idea if
			// there's a
			// constructor with a Map
			// parameter
			setField(field, instance, instantiate(type, new Class[] { Map.class }, serializedData));
		    } else {
			System.out.println("Set ConfigurationSerializable: " + type.getSimpleName() + " data: " + serializedData);
			setField(field, instance, serializedData);
		    }
		} else if (RAW_INSERTABLE_CLASSES.contains(type)) {
		    System.out.println("Set raw data: {" + type.getSimpleName() + "= '" + serializedData + "'}");
		    setField(field, instance, serializedData);
		} else {
		    throw new IllegalArgumentException("No deserialize method found for field " + field.getName() + " of type " + type.getName());
		}
	    }
	}

	return instance;
    }

    /**
     * Returns the value of a field
     *
     * @param f
     *            The Field to get the value from
     * @param handle
     *            The handle to get it from
     * @param <T>
     *            The Type to cast it to
     *
     * @return The value or null if an error occurred
     */
    @SuppressWarnings("unchecked")
    private static <T> T getField(Field f, Object handle) {
	try {
	    if (!f.isAccessible()) {
		f.setAccessible(true);
	    }
	    // noinspection unchecked
	    return (T) f.get(handle);
	} catch (IllegalAccessException e) {
	    e.printStackTrace();
	}
	return null;
    }

    /**
     * Sets the value of a field
     *
     * @param field
     *            The field to set the value for
     * @param handle
     *            The handle to set it for
     * @param value
     *            The value to set it to
     */
    private static void setField(Field field, Object handle, Object value) {
	try {
	    if (!field.isAccessible()) {
		field.setAccessible(true);
	    }
	    field.set(handle, value);
	} catch (IllegalAccessException e) {
	    e.printStackTrace();
	}
    }

    /**
     * Instantiates an object
     *
     * @param constructor
     *            The constructor to instantiate
     * @param params
     *            The parameters to pass
     * @param <T>
     *            The Type to cast it to
     *
     * @return The instantiated object or null if an error occurred
     */
    @SuppressWarnings("unchecked")
    private static <T> T instantiate(Constructor<?> constructor, Object... params) {
	try {
	    constructor.setAccessible(true);
	    return (T) constructor.newInstance(params);
	} catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
	    e.printStackTrace();
	}
	return null;
    }

    /**
     * Instantiates a class using the base constructor (no params)
     *
     * @param clazz
     *            The class to instantiate
     * @param <T>
     *            The Type to cast it to
     *
     * @return The instantiated object or null if an error occurred
     */
    private static <T> T instantiate(Class<?> clazz) {
	try {
	    Constructor<?> constructor = clazz.getDeclaredConstructor();
	    return instantiate(constructor);
	} catch (NoSuchMethodException e) {
	    e.printStackTrace();
	    System.out.println("Check if the class " + clazz.getName() + " contains a default constructor.");
	}
	return null;
    }

    /**
     * Instantiates an object
     *
     * @param clazz
     *            The class to instantiate
     * @param classes
     *            The parameter classes
     * @param params
     *            The parameters
     * @param <T>
     *            The Type to cast it to
     *
     * @return The instantiated object or null if an error occurred
     */
    private static <T> T instantiate(Class<?> clazz, Class<?>[] classes, Object... params) {
	try {
	    Constructor<?> constructor = clazz.getDeclaredConstructor(classes);
	    return instantiate(constructor, params);
	} catch (NoSuchMethodException e) {
	    e.printStackTrace();
	    // TODO: @I_Al_Istannen: This is an inaccurate message
	    System.out.println("Check if the class " + clazz.getName() + " contains a default constructor.");
	}
	return null;
    }

    /**
     * Returns all the fields that need to be serialized
     *
     * @param clazz
     *            The class to get all the fields from
     *
     * @return All the fields that need to be serialized
     */
    private static List<Field> getFieldsToSerialize(Class<?> clazz) {
	return Arrays.stream(clazz.getDeclaredFields()).filter(field -> !Modifier.isTransient(field.getModifiers())).filter(field -> !Modifier.isStatic(field.getModifiers()))
		.collect(Collectors.toList());
    }
}
